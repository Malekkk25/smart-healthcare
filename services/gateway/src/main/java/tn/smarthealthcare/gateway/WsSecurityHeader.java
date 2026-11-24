package tn.smarthealthcare.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * En-tête WS-Security UsernameToken avec mot de passe haché (profil OASIS « PasswordDigest ») :
 * <pre>Password = Base64( SHA-1( nonce + created + mot de passe ) )</pre>
 * Le nonce et la date rendent chaque en-tête unique : un en-tête rejoué est refusé par le serveur.
 */
public final class WsSecurityHeader {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WsSecurityHeader() {}

    public static String digest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonce);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Élément wsse:Security prêt à être placé dans l'en-tête SOAP (aucun préfixe emprunté à l'enveloppe). */
    public static String securityElement(String username, String password) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return securityElement(username, password, nonce, Instant.now());
    }

    static String securityElement(String username, String password, byte[] nonce, Instant now) {
        String created = now.truncatedTo(ChronoUnit.SECONDS).toString();
        return "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\""
                + " xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\""
                + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:mustUnderstand=\"1\">"
                + "<wsse:UsernameToken><wsse:Username>" + escape(username) + "</wsse:Username>"
                + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">"
                + digest(nonce, created, password) + "</wsse:Password>"
                + "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce>"
                + "<wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
