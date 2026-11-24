package tn.smarthealthcare.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tn.smarthealthcare.orchestrator.Ports.DispatchOutcome;
import tn.smarthealthcare.orchestrator.Ports.DroitsOutcome;
import tn.smarthealthcare.orchestrator.Ports.NoResourceException;
import tn.smarthealthcare.orchestrator.Ports.RefusedException;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;
import tn.smarthealthcare.orchestrator.clients.GraphQlDossierClient;
import tn.smarthealthcare.orchestrator.clients.RestPortalClient;
import tn.smarthealthcare.orchestrator.clients.SoapEhrClient;

/** Les vrais clients HTTP face à un faux serveur : distinction résultat métier / panne. */
class ClientsTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastCorrelation = new AtomicReference<>();
    private final AtomicReference<String> lastUpgrade = new AtomicReference<>("non lu");

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int status, String contentType, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastCorrelation.set(ex.getRequestHeaders().getFirst("X-Correlation-Id"));
            lastUpgrade.set(ex.getRequestHeaders().getFirst("Upgrade"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static OrchestratorProperties props(String base) {
        return props(base, "");
    }

    private static OrchestratorProperties props(String base, String ehrPassword) {
        return new OrchestratorProperties(base + "/ws/ehr", base, base + "/graphql", base, "localhost:1", "C", "CRITIQUE",
                1500, 3, 1, 0, 5, "ehr-client", ehrPassword);
    }

    private static final String SOAP_OK = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
            <ns2:verifierDroitsCNAMResponse xmlns:ns2="http://smarthealthcare.tn/ehr">
              <ns2:droitsOuverts>true</ns2:droitsOuverts><ns2:tauxRemboursement>80</ns2:tauxRemboursement>
              <ns2:motif>acte CS pris en charge</ns2:motif></ns2:verifierDroitsCNAMResponse></soapenv:Body></soapenv:Envelope>""";

    private static String fault(String detailName) {
        return """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
                <faultcode>soap:Server</faultcode><faultstring>x</faultstring><detail>
                <ns2:%s xmlns:ns2="http://smarthealthcare.tn/ehr"><ns2:patientId>PAT-000010</ns2:patientId>
                <ns2:message>Les droits CNAM du patient ont expiré</ns2:message></ns2:%s></detail></soap:Fault></soap:Body></soap:Envelope>"""
                .formatted(detailName, detailName);
    }

    // ------------------------------------------------------------------ SOAP

    @Test
    void soapReponseNormale() throws Exception {
        var client = new SoapEhrClient(props(serve(200, "text/xml", SOAP_OK)));
        DroitsOutcome o = client.verifierDroits("PAT-000002", "CS", "cid-1");
        assertTrue(o.ouverts());
        assertEquals(80, o.taux());
        assertFalse(o.isFault());
        assertEquals("cid-1", lastCorrelation.get(), "X-Correlation-Id propagé");
        assertTrue(lastBody.get().contains("<ehr:patientId>PAT-000002</ehr:patientId>"));
    }

    @Test
    void soapDroitsExpiresEstUnResultat() throws Exception {
        var client = new SoapEhrClient(props(serve(500, "text/xml", fault("DroitsExpiresFault"))));
        DroitsOutcome o = client.verifierDroits("PAT-000010", "C", "cid");
        assertTrue(o.isFault());
        assertEquals("DroitsExpiresFault", o.fault());
        assertEquals("Les droits CNAM du patient ont expiré", o.faultMessage());
    }

    @Test
    void soapPatientInconnuEstUnResultat() throws Exception {
        var client = new SoapEhrClient(props(serve(500, "text/xml", fault("PatientInconnuFault"))));
        assertEquals("PatientInconnuFault", client.verifierDroits("PAT-000099", "C", "cid").fault());
    }

    @Test
    void soapErreurTechniqueEstUnePanne() throws Exception {
        var client = new SoapEhrClient(props(serve(500, "text/html", "<html>Bad gateway</html>")));
        assertThrows(ServiceUnavailableException.class, () -> client.verifierDroits("PAT-000002", "C", "cid"));
    }

    @Test
    void soapInjoignableEstUnePanne() throws Exception {
        String base = serve(200, "text/xml", SOAP_OK);
        server.stop(0);
        var client = new SoapEhrClient(props(base));
        assertThrows(ServiceUnavailableException.class, () -> client.verifierDroits("PAT-000002", "C", "cid"));
    }

    @Test
    void soapRefuseLesIdentifiantsSuspects() throws Exception {
        var client = new SoapEhrClient(props(serve(200, "text/xml", SOAP_OK)));
        assertThrows(RefusedException.class, () -> client.verifierDroits("PAT-1</ehr:patientId><x/>", "C", "cid"));
    }

    // ------------------------------------------------------------------ WS-Security

    @Test
    void soapSansMotDePasseDeServiceNEnvoieAucunEnteteDeSecurite() throws Exception {
        new SoapEhrClient(props(serve(200, "text/xml", SOAP_OK), "")).verifierDroits("PAT-000002", "CS", "cid");
        assertFalse(lastBody.get().contains("Security"));
    }

    @Test
    void soapAvecMotDePasseDeServiceEnvoieUnUsernameTokenHache() throws Exception {
        new SoapEhrClient(props(serve(200, "text/xml", SOAP_OK), "secret")).verifierDroits("PAT-000002", "CS", "cid");
        String xml = lastBody.get();
        assertTrue(xml.contains("<wsse:Username>ehr-client</wsse:Username>"));
        assertTrue(xml.contains("#PasswordDigest"));
        assertFalse(xml.contains(">secret<"), "le mot de passe ne circule jamais en clair");
        // l'en-tête précède le corps de l'enveloppe
        assertTrue(xml.indexOf("<soapenv:Header>") < xml.indexOf("<soapenv:Body>"));
    }

    @Test
    void chaqueAppelPorteUnNonceNeuf() throws Exception {
        var client = new SoapEhrClient(props(serve(200, "text/xml", SOAP_OK), "secret"));
        client.verifierDroits("PAT-000002", "CS", "cid");
        String premier = lastBody.get().replaceAll("(?s).*<wsse:Nonce[^>]*>([^<]*)</wsse:Nonce>.*", "$1");
        client.verifierDroits("PAT-000002", "CS", "cid");
        String second = lastBody.get().replaceAll("(?s).*<wsse:Nonce[^>]*>([^<]*)</wsse:Nonce>.*", "$1");
        assertTrue(!premier.equals(second) && !premier.isBlank(), "nonce rejoué : " + premier);
    }

    @Test
    void leHacheEstCeluiAttenduParWss4j() {
        // Vecteur de test commun aux trois modules ; WSS4J (soap-ehr) calcule la même valeur
        assertEquals("IKtlI2J6LyBZlHTY7s1cyxXVh7M=",
                tn.smarthealthcare.orchestrator.clients.WsSecurityHeader.digest(
                        "0123456789abcdef".getBytes(StandardCharsets.UTF_8), "2026-11-24T12:00:00Z", "secret"));
    }

    // ------------------------------------------------------------------ REST

    @Test
    void restDispatchReussi() throws Exception {
        var client = new RestPortalClient(props(serve(201, "application/json",
                "{\"dispatchId\":\"DSP-1\",\"ambulanceId\":\"AMB-03\",\"etaMinutes\":7}")));
        DispatchOutcome d = client.dispatch("a1", "PAT-000004", "CRITIQUE", 36.8, 10.18, "cid-2");
        assertEquals("AMB-03", d.ambulanceId());
        assertEquals(7, d.etaMinutes());
        assertNull(d.fallbackAppointmentId());
        assertEquals("cid-2", lastCorrelation.get());
        assertTrue(lastBody.get().contains("\"alertId\":\"a1\""));
    }

    @Test
    void lesClientsNeDemandentPasDeMiseAJourHttp2() throws Exception {
        // Régression : « Upgrade: h2c » faisait refuser les requêtes par uvicorn (service IA), qui n'était donc jamais utilisé
        var client = new RestPortalClient(props(serve(201, "application/json", "{\"dispatchId\":\"DSP-1\",\"ambulanceId\":\"AMB-03\",\"etaMinutes\":7}")));
        client.dispatch("a1", "PAT-000004", "CRITIQUE", 36.8, 10.18, "cid");
        assertNull(lastUpgrade.get(), "l'en-tête Upgrade ne doit pas être envoyé");
    }

    @Test
    void restRepliSurCreneauRepereDansLaReponse() throws Exception {
        var client = new RestPortalClient(props(serve(201, "application/json",
                "{\"dispatchId\":\"DSP-2\",\"ambulanceId\":null,\"etaMinutes\":null,\"fallbackAppointmentId\":\"APT-2026-000009\"}")));
        assertEquals("APT-2026-000009", client.dispatch("a1", "PAT-000004", "CRITIQUE", 36.8, 10.18, "cid").fallbackAppointmentId());
    }

    @Test
    void rest409SignifiePlusAucuneRessource() throws Exception {
        var client = new RestPortalClient(props(serve(409, "application/json",
                "{\"code\":\"AUCUNE_RESSOURCE_DISPONIBLE\",\"message\":\"rien\",\"correlationId\":\"c\"}")));
        assertThrows(NoResourceException.class, () -> client.dispatch("a1", "PAT-000004", "CRITIQUE", 36.8, 10.18, "cid"));
    }

    @Test
    void rest404EstUnRefusMetier() throws Exception {
        var client = new RestPortalClient(props(serve(404, "application/json", "{\"code\":\"RESSOURCE_INTROUVABLE\"}")));
        assertThrows(RefusedException.class, () -> client.dispatch("a1", "PAT-000999", "CRITIQUE", 36.8, 10.18, "cid"));
    }

    @Test
    void rest503EstUnePanne() throws Exception {
        var client = new RestPortalClient(props(serve(503, "application/json", "{}")));
        assertThrows(ServiceUnavailableException.class, () -> client.dispatch("a1", "PAT-000004", "CRITIQUE", 36.8, 10.18, "cid"));
    }

    // ------------------------------------------------------------------ GraphQL

    @Test
    void graphqlRenvoieLaFiche() throws Exception {
        var client = new GraphQlDossierClient(props(serve(200, "application/json",
                "{\"data\":{\"patient\":{\"id\":\"PAT-000004\",\"nom\":\"Bouzid\"}}}")));
        assertEquals("Bouzid", client.fetch("PAT-000004", "cid").get("nom"));
    }

    @Test
    void graphqlReponsePartielleEstAcceptee() throws Exception {
        var client = new GraphQlDossierClient(props(serve(200, "application/json",
                "{\"data\":{\"patient\":{\"id\":\"PAT-000004\",\"nom\":\"Bouzid\",\"treatment\":null}},\"errors\":[{\"message\":\"REST indisponible\"}]}")));
        var fiche = client.fetch("PAT-000004", "cid");
        assertEquals("Bouzid", fiche.get("nom"));
        assertTrue(fiche.containsKey("erreursPartielles"));
    }

    @Test
    void graphqlPatientIntrouvable() throws Exception {
        var client = new GraphQlDossierClient(props(serve(200, "application/json", "{\"data\":{\"patient\":null}}")));
        assertThrows(RefusedException.class, () -> client.fetch("PAT-000999", "cid"));
    }

    @Test
    void graphqlEnErreurEstUnePanne() throws Exception {
        var client = new GraphQlDossierClient(props(serve(200, "application/json", "{\"data\":null,\"errors\":[{\"message\":\"boom\"}]}")));
        assertThrows(ServiceUnavailableException.class, () -> client.fetch("PAT-000004", "cid"));
    }
}
