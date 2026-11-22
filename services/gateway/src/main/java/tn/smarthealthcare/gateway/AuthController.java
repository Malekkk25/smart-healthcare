package tn.smarthealthcare.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** Connexion : émet un JWT pour les comptes de démonstration (voir contracts/conventions.md). */
@RestController
public class AuthController {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    public record LoginRequest(String username, String password) {}

    private final AppProperties props;
    private final JwtService jwt;

    public AuthController(AppProperties props, JwtService jwt) {
        this.props = props;
        this.jwt = jwt;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) LoginRequest body, ServerWebExchange exchange) {
        String correlationId = ApiErrors.correlationId(exchange);
        if (body == null || body.username() == null || body.password() == null) {
            return ResponseEntity.badRequest().body(ApiErrors.corps("REQUETE_INVALIDE",
                    "Les champs 'username' et 'password' sont obligatoires.", correlationId));
        }
        var utilisateur = props.users().stream().filter(u -> u.username().equals(body.username())).findFirst();
        boolean motDePasseOk = MessageDigest.isEqual(
                props.demoPassword().getBytes(StandardCharsets.UTF_8), body.password().getBytes(StandardCharsets.UTF_8));
        if (utilisateur.isEmpty() || !motDePasseOk) {
            audit.warn("AUDIT login refusé user={} correlationId={}", body.username(), correlationId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiErrors.corps("NON_AUTHENTIFIE", "Identifiants invalides.", correlationId));
        }
        var u = utilisateur.get();
        String token = jwt.emettre(u.username(), u.role(), u.patientId());
        audit.info("AUDIT login réussi user={} role={} correlationId={}", u.username(), u.role(), correlationId);

        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("token", token);
        reponse.put("tokenType", "Bearer");
        reponse.put("expiresIn", jwt.ttlSeconds());
        reponse.put("role", u.role());
        reponse.put("patientId", u.patientId());
        return ResponseEntity.ok(reponse);
    }
}
