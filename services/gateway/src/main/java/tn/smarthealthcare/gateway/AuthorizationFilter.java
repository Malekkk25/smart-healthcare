package tn.smarthealthcare.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Valide le JWT, applique la matrice de rôles (AccessRules), vérifie qu'un PATIENT n'accède qu'à ses données,
 * puis transmet l'identité aux services internes (X-User-Id, X-User-Role, X-Patient-Id).
 * Chaque requête est tracée dans le journal d'audit (logger "audit").
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthorizationFilter implements WebFilter {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    private final JwtService jwt;
    private final AccessRules rules;

    public AuthorizationFilter(JwtService jwt, AccessRules rules) {
        this.jwt = jwt;
        this.rules = rules;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        String path = request.getPath().pathWithinApplication().value();

        // Les requêtes de pré-vérification CORS sont gérées par la configuration globalcors
        if (HttpMethod.OPTIONS.equals(method)) {
            return chain.filter(exchange);
        }

        // Ces en-têtes ne doivent jamais venir du client : on les écrase toujours
        ServerWebExchange propre = exchange.mutate().request(r -> r.headers(h -> {
            h.remove("X-User-Id");
            h.remove("X-User-Role");
            h.remove("X-Patient-Id");
        })).build();

        AccessRules.Match match = rules.trouver(method, path);
        if (match != null && match.rule().pub()) {
            return chain.filter(propre);
        }

        String token = extraireJeton(propre);
        if (token == null) {
            return refuser(propre, null, HttpStatus.UNAUTHORIZED, "NON_AUTHENTIFIE", "Jeton JWT absent.");
        }
        Claims claims;
        try {
            claims = jwt.verifier(token);
        } catch (JwtException | IllegalArgumentException e) {
            return refuser(propre, null, HttpStatus.UNAUTHORIZED, "NON_AUTHENTIFIE", "Jeton JWT invalide ou expiré.");
        }
        String user = claims.getSubject();
        String role = claims.get("role", String.class);
        String patientId = claims.get("patientId", String.class);

        if (match != null) {
            var rule = match.rule();
            if (!rule.roles().isEmpty() && !rule.roles().contains(role)) {
                return refuser(propre, user, HttpStatus.FORBIDDEN, "ACCES_REFUSE",
                        "Le rôle " + role + " n'a pas accès à cette ressource.");
            }
            if (AccessRules.PATIENT.equals(role)) {
                if (rule.selfVar() != null && !match.variables().get(rule.selfVar()).equals(patientId)) {
                    return refuser(propre, user, HttpStatus.FORBIDDEN, "ACCES_REFUSE",
                            "Un patient ne peut accéder qu'à ses propres données.");
                }
                if (rule.selfQuery() != null) {
                    String demande = request.getQueryParams().getFirst(rule.selfQuery());
                    if (demande != null && !demande.equals(patientId)) {
                        return refuser(propre, user, HttpStatus.FORBIDDEN, "ACCES_REFUSE",
                                "Un patient ne peut accéder qu'à ses propres données.");
                    }
                }
            }
        }

        ServerWebExchange authentifie = propre.mutate().request(r -> r.headers(h -> {
            h.set("X-User-Id", user);
            h.set("X-User-Role", role);
            if (patientId != null) {
                h.set("X-Patient-Id", patientId);
            }
        })).build();

        return chain.filter(authentifie).doFinally(signal -> audit.info(
                "AUDIT user={} role={} patientId={} {} {} -> {} correlationId={}", user, role, patientId, method, path,
                statut(authentifie), ApiErrors.correlationId(authentifie)));
    }

    /** Authorization: Bearer ... ; pour les WebSocket (pas d'en-tête possible dans le navigateur) : ?access_token= */
    private static String extraireJeton(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String upgrade = exchange.getRequest().getHeaders().getUpgrade();
        if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
            return exchange.getRequest().getQueryParams().getFirst("access_token");
        }
        return null;
    }

    private Mono<Void> refuser(ServerWebExchange exchange, String user, HttpStatus status, String code,
                               String message) {
        audit.warn("AUDIT user={} {} {} -> {} {} correlationId={}", user, exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().pathWithinApplication().value(), status.value(), code,
                ApiErrors.correlationId(exchange));
        return ApiErrors.ecrire(exchange, status, code, message);
    }

    private static Object statut(ServerWebExchange exchange) {
        var s = exchange.getResponse().getStatusCode();
        return s != null ? s.value() : "?";
    }
}
