package tn.smarthealthcare.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SoapSecurityInjectorTest {

    private static final String BODY = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ehr="http://smarthealthcare.tn/ehr">\
            <soapenv:Body><ehr:verifierDroitsCNAMRequest><ehr:patientId>PAT-000002</ehr:patientId>\
            <ehr:codeActe>CS</ehr:codeActe></ehr:verifierDroitsCNAMRequest></soapenv:Body></soapenv:Envelope>""";

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    @Test
    void leHacheEstCeluiAttenduParWss4j() {
        // Même vecteur de test que dans soap-ehr (WSS4J) et dans l'orchestrateur
        assertEquals("IKtlI2J6LyBZlHTY7s1cyxXVh7M=",
                WsSecurityHeader.digest("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "2026-11-24T12:00:00Z", "secret"));
    }

    @Test
    void ajouteUnEnteteAvantLeCorps() {
        String out = SoapSecurityInjector.inject(BODY, "ehr-client", "secret");
        assertTrue(out.indexOf("Header") >= 0 && out.indexOf("Header") < out.indexOf("Body"));
        assertTrue(out.contains("<wsse:Username>ehr-client</wsse:Username>"));
        assertTrue(out.contains("PasswordDigest"));
        assertFalse(out.contains(">secret<"), "jamais de mot de passe en clair");
        assertTrue(out.contains("PAT-000002"), "le corps de la requête est conservé");
    }

    @Test
    void remplaceUnEnteteDeSecuriteFourniParLeClient() {
        String forge = BODY.replace("<soapenv:Body>",
                "<soapenv:Header><wsse:Security xmlns:wsse=\"" + SoapSecurityInjector.WSSE_NS + "\"><wsse:UsernameToken>"
                        + "<wsse:Username>pirate</wsse:Username></wsse:UsernameToken></wsse:Security></soapenv:Header><soapenv:Body>");
        String out = SoapSecurityInjector.inject(forge, "ehr-client", "secret");
        assertFalse(out.contains("pirate"), "l'en-tête fourni par le client est supprimé");
        assertEquals(1, count(out, "<wsse:Username>"));
        assertTrue(out.contains("ehr-client"));
    }

    @Test
    void conserveLesAutresEntetes() {
        String avec = BODY.replace("<soapenv:Body>", "<soapenv:Header><x:Trace xmlns:x=\"urn:x\">abc</x:Trace></soapenv:Header><soapenv:Body>");
        String out = SoapSecurityInjector.inject(avec, "ehr-client", "secret");
        assertTrue(out.contains("<x:Trace xmlns:x=\"urn:x\">abc</x:Trace>"));
        assertTrue(out.contains("wsse:UsernameToken"));
    }

    @Test
    void chaqueEnveloppeRecoitUnNonceNeuf() {
        String a = SoapSecurityInjector.inject(BODY, "ehr-client", "secret");
        String b = SoapSecurityInjector.inject(BODY, "ehr-client", "secret");
        assertFalse(a.replaceAll("(?s).*<wsse:Nonce[^>]*>([^<]*)</wsse:Nonce>.*", "$1")
                .equals(b.replaceAll("(?s).*<wsse:Nonce[^>]*>([^<]*)</wsse:Nonce>.*", "$1")));
    }

    @Test
    void sansMotDePasseDeServiceLEnveloppeNEstPasModifiee() {
        assertEquals(BODY, SoapSecurityInjector.inject(BODY, "ehr-client", ""));
        assertEquals(BODY, SoapSecurityInjector.inject(BODY, "ehr-client", null));
    }

    @Test
    void uneRequeteQuiNEstPasUneEnveloppeSoapPasseTelleQuelle() {
        assertEquals("pas du xml", SoapSecurityInjector.inject("pas du xml", "ehr-client", "secret"));
        String autre = "<a><b/></a>";
        assertEquals(autre, SoapSecurityInjector.inject(autre, "ehr-client", "secret"));
    }

    @Test
    void refuseLesEntitesExternes() {
        String xxe = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + BODY;
        assertEquals(xxe, SoapSecurityInjector.inject(xxe, "ehr-client", "secret"));
    }
}
