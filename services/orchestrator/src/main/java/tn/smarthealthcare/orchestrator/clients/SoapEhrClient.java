package tn.smarthealthcare.orchestrator.clients;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tn.smarthealthcare.orchestrator.OrchestratorProperties;
import tn.smarthealthcare.orchestrator.Ports.DroitsOutcome;
import tn.smarthealthcare.orchestrator.Ports.EhrPort;
import tn.smarthealthcare.orchestrator.Ports.RefusedException;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;

/**
 * Client du service SOAP (contracts/ehr/ehr.wsdl, document/literal). Distingue :
 * un SOAP Fault métier (droits expirés, patient inconnu) = un RÉSULTAT ; une panne (réseau, délai, 5xx sans Fault) = une exception.
 */
@Component
public class SoapEhrClient implements EhrPort {

    private static final String NS = "http://smarthealthcare.tn/ehr";
    private static final Pattern PATIENT = Pattern.compile("^PAT-\\d{6}$");
    private static final Pattern ACTE = Pattern.compile("^[A-Z]{1,4}$");

    private record Raw(int status, String body) {}

    private final RestClient rest;
    private final String url;

    public SoapEhrClient(OrchestratorProperties props) {
        this.rest = Http.client(props.timeoutMs());
        this.url = props.ehrUrl();
    }

    @Override
    public DroitsOutcome verifierDroits(String patientId, String codeActe, String correlationId) {
        check(patientId, codeActe);
        Raw raw = call("verifierDroitsCNAM",
                "<ehr:patientId>" + patientId + "</ehr:patientId><ehr:codeActe>" + codeActe + "</ehr:codeActe>", correlationId);
        Document doc = parse(raw.body());
        DroitsOutcome fault = fault(doc);
        if (fault != null) return fault;
        if (raw.status() != 200) throw new ServiceUnavailableException("SOAP : HTTP " + raw.status(), null);
        return new DroitsOutcome("true".equals(text(doc, "droitsOuverts")),
                parseInt(text(doc, "tauxRemboursement")), text(doc, "motif"), null, null);
    }

    @Override
    public int compterActes(String patientId, String correlationId) {
        check(patientId, "C");
        Raw raw = call("consulterHistorique", "<ehr:patientId>" + patientId + "</ehr:patientId>", correlationId);
        Document doc = parse(raw.body());
        if (fault(doc) != null || raw.status() != 200) return 0;
        return doc.getElementsByTagNameNS(NS, "acte").getLength();
    }

    // ------------------------------------------------------------------ appel et analyse

    private Raw call(String operation, String content, String correlationId) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ehr=\"" + NS + "\">"
                + "<soapenv:Body><ehr:" + operation + "Request>" + content + "</ehr:" + operation + "Request></soapenv:Body></soapenv:Envelope>";
        try {
            return rest.post().uri(url)
                    .contentType(MediaType.parseMediaType("text/xml;charset=UTF-8"))
                    .header("SOAPAction", NS + "/" + operation)
                    .header("X-Correlation-Id", correlationId)
                    .body(xml)
                    .exchange((req, res) -> new Raw(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw new ServiceUnavailableException("SOAP injoignable : " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("SOAP : " + e, e);
        }
    }

    private static void check(String patientId, String acte) {
        if (!PATIENT.matcher(patientId).matches() || !ACTE.matcher(acte).matches()) {
            throw new RefusedException("identifiant patient ou acte invalide");
        }
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ServiceUnavailableException("réponse SOAP illisible", e);
        }
    }

    /** Un Fault dont le détail est PatientInconnuFault ou DroitsExpiresFault est un résultat métier ; tout autre Fault est une panne. */
    private static DroitsOutcome fault(Document doc) {
        var faults = doc.getElementsByTagNameNS("*", "Fault");
        if (faults.getLength() == 0) return null;
        Element fault = (Element) faults.item(0);
        var detail = fault.getElementsByTagName("detail");
        if (detail.getLength() > 0) {
            for (Node n = detail.item(0).getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && (e.getLocalName().equals("DroitsExpiresFault") || e.getLocalName().equals("PatientInconnuFault"))) {
                    String message = null;
                    var m = e.getElementsByTagNameNS(NS, "message");
                    if (m.getLength() > 0) message = m.item(0).getTextContent();
                    return new DroitsOutcome(false, null, null, e.getLocalName(), message);
                }
            }
        }
        var text = fault.getElementsByTagName("faultstring");
        throw new ServiceUnavailableException("SOAP Fault technique : " + (text.getLength() > 0 ? text.item(0).getTextContent() : "?"), null);
    }

    private static String text(Document doc, String local) {
        var l = doc.getElementsByTagNameNS(NS, local);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private static Integer parseInt(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
