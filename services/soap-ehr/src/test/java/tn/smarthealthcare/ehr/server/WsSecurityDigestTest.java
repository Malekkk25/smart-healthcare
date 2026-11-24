package tn.smarthealthcare.ehr.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.wss4j.common.util.UsernameTokenUtil;
import org.junit.jupiter.api.Test;

/**
 * Le haché de mot de passe que fabriquent la Gateway et l'orchestrateur doit être exactement celui que WSS4J (côté serveur)
 * recalcule. Ce vecteur de test est le même dans les trois modules (calculé de façon indépendante).
 */
class WsSecurityDigestTest {

    static final String NONCE = "0123456789abcdef";
    static final String CREATED = "2026-11-24T12:00:00Z";
    static final String PASSWORD = "secret";
    static final String EXPECTED = "IKtlI2J6LyBZlHTY7s1cyxXVh7M=";

    @Test
    void wss4jCalculeLeMemeHacheQueLesClients() throws Exception {
        String digest = UsernameTokenUtil.doPasswordDigest(NONCE.getBytes(StandardCharsets.UTF_8), CREATED, PASSWORD);
        assertEquals(EXPECTED, digest);
    }

    @Test
    void leHacheEstDeBase64DunSha1() {
        assertEquals(20, Base64.getDecoder().decode(EXPECTED).length, "SHA-1 = 20 octets");
    }
}
