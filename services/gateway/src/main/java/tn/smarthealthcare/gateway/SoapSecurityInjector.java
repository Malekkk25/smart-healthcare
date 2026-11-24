package tn.smarthealthcare.gateway;

import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Ajoute à une enveloppe SOAP 1.1 l'en-tête WS-Security UsernameToken du compte de service.
 * Tout en-tête de sécurité fourni par le client est REMPLACÉ : un client ne peut pas se faire passer pour le compte de service.
 */
public final class SoapSecurityInjector {

    private static final Logger log = LoggerFactory.getLogger(SoapSecurityInjector.class);
    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private SoapSecurityInjector() {}

    /** @return l'enveloppe avec l'en-tête de sécurité ; inchangée si le mot de passe est vide ou si le corps n'est pas une enveloppe SOAP 1.1 */
    public static String inject(String xml, String username, String password) {
        if (password == null || password.isBlank() || xml == null || xml.isBlank()) return xml;
        try {
            Document doc = parse(xml);
            Element envelope = doc.getDocumentElement();
            if (!SOAP_NS.equals(envelope.getNamespaceURI()) || !"Envelope".equals(envelope.getLocalName())) return xml;

            Element header = child(envelope, SOAP_NS, "Header");
            if (header == null) {
                String prefix = envelope.getPrefix();
                header = doc.createElementNS(SOAP_NS, prefix == null ? "Header" : prefix + ":Header");
                envelope.insertBefore(header, envelope.getFirstChild());
            }
            // un client ne peut pas fournir lui-même l'en-tête de sécurité
            for (Node n = header.getFirstChild(), next; n != null; n = next) {
                next = n.getNextSibling();
                if (n instanceof Element e && WSSE_NS.equals(e.getNamespaceURI()) && "Security".equals(e.getLocalName())) {
                    header.removeChild(n);
                }
            }
            Element security = parse(WsSecurityHeader.securityElement(username, password)).getDocumentElement();
            header.appendChild(doc.importNode(security, true));
            return serialize(doc);
        } catch (Exception e) {
            log.warn("enveloppe SOAP illisible, transmise sans en-tête de sécurité : {}", e.toString());
            return xml;
        }
    }

    private static Element child(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }
}
