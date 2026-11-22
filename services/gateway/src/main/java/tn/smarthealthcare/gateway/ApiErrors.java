package tn.smarthealthcare.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Écrit les erreurs au format commun {code, message, correlationId}. */
public final class ApiErrors {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiErrors() {}

    public static String correlationId(ServerWebExchange exchange) {
        String id = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        return id != null ? id : "";
    }

    public static Map<String, String> corps(String code, String message, String correlationId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("correlationId", correlationId);
        return m;
    }

    public static Mono<Void> ecrire(ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(CorrelationIdFilter.HEADER, correlationId(exchange));
        try {
            byte[] bytes = JSON.writeValueAsBytes(corps(code, message, correlationId(exchange)));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            byte[] fallback = "{\"code\":\"ERREUR_INTERNE\"}".getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(fallback)));
        }
    }
}
