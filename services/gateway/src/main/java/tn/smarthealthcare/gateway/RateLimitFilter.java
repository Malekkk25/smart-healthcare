package tn.smarthealthcare.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Limitation de débit par client (adresse IP), fenêtre fixe d'une minute, en mémoire.
 * Limite générale pour l'API et limite plus stricte pour la connexion (anti force brute).
 * Au-delà : 429 {code: TROP_DE_REQUETES} avec l'en-tête Retry-After.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RateLimitFilter implements WebFilter {

    private static final long FENETRE_MS = 60_000;

    private record Fenetre(long debut, int compteur) {}

    private final Map<String, Fenetre> fenetres = new ConcurrentHashMap<>();
    private final int limiteGenerale;
    private final int limiteConnexion;

    public RateLimitFilter(AppProperties props) {
        this.limiteGenerale = props.rateLimit().requestsPerMinute();
        this.limiteConnexion = props.rateLimit().loginPerMinute();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (HttpMethod.OPTIONS.equals(request.getMethod()) || path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        String ip = request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress() : "inconnu";
        boolean connexion = path.equals("/api/auth/login");
        String cle = (connexion ? "login:" : "api:") + ip;
        int limite = connexion ? limiteConnexion : limiteGenerale;

        long maintenant = System.currentTimeMillis();
        if (fenetres.size() > 10_000) {
            fenetres.entrySet().removeIf(e -> maintenant - e.getValue().debut() > FENETRE_MS);
        }
        Fenetre f = fenetres.merge(cle, new Fenetre(maintenant, 1),
                (ancienne, nouvelle) -> maintenant - ancienne.debut() >= FENETRE_MS
                        ? nouvelle : new Fenetre(ancienne.debut(), ancienne.compteur() + 1));

        if (f.compteur() > limite) {
            long reste = Math.max(1, (FENETRE_MS - (maintenant - f.debut()) + 999) / 1000);
            exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(reste));
            return ApiErrors.ecrire(exchange, HttpStatus.TOO_MANY_REQUESTS, "TROP_DE_REQUETES",
                    "Trop de requêtes : réessayez dans " + reste + " s.");
        }
        return chain.filter(exchange);
    }
}
