package tn.smarthealthcare.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Émission et vérification des JWT (HS256). Champs : sub, role, patientId (PATIENT uniquement), iat, exp. */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = props.jwt().ttlSeconds();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String emettre(String username, String role, String patientId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (patientId != null) {
            builder.claim("patientId", patientId);
        }
        return builder.signWith(key).compact();
    }

    /** @throws JwtException si le jeton est invalide, falsifié ou expiré */
    public Claims verifier(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
