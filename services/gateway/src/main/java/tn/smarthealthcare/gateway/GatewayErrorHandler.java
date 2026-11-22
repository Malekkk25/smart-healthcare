package tn.smarthealthcare.gateway;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Convertit les erreurs de routage (service arrêté, introuvable, lent) au format commun. */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            // Service arrêté ou absent : connexion refusée, ou nom Docker non résolu (conteneur stoppé/inexistant)
            String message = t.getMessage() != null ? t.getMessage() : "";
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t.getClass().getSimpleName().contains("Dns")
                    || message.contains("Connection refused") || message.contains("NXDOMAIN")
                    || message.contains("Failed to resolve")) {
                return service(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Service injoignable.", ex);
            }
            if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return service(exchange, HttpStatus.GATEWAY_TIMEOUT, "Le service n'a pas répondu à temps.", ex);
            }
        }
        if (ex instanceof ResponseStatusException rse) {
            int code = rse.getStatusCode().value();
            if (code == 404) {
                return ApiErrors.ecrire(exchange, HttpStatus.NOT_FOUND, "RESSOURCE_INTROUVABLE", "Route inconnue.");
            }
            if (code == 502 || code == 503 || code == 504) {
                return service(exchange, HttpStatus.valueOf(code), "Service indisponible.", ex);
            }
            return ApiErrors.ecrire(exchange, rse.getStatusCode(), "REQUETE_INVALIDE",
                    rse.getReason() != null ? rse.getReason() : "Requête invalide.");
        }
        log.error("Erreur inattendue dans la gateway", ex);
        return ApiErrors.ecrire(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE",
                "Erreur interne de la gateway.");
    }

    private Mono<Void> service(ServerWebExchange exchange, HttpStatus status, String message, Throwable ex) {
        log.warn("{} correlationId={} : {}", message, ApiErrors.correlationId(exchange), ex.toString());
        return ApiErrors.ecrire(exchange, status, "SERVICE_INDISPONIBLE", message);
    }
}
