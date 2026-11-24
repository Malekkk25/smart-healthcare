package tn.smarthealthcare.dashboard;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Client du service REST du portail (profil et ordonnances). Propage X-Correlation-Id. */
@Component
public class PatientsClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LISTE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> OBJET = new ParameterizedTypeReference<>() {};

    private final WebClient web;

    public PatientsClient(DashboardProperties props, WebClient.Builder builder) {
        // Résolution DNS du JDK (cache court) : le résolveur de Netty garde l'IP d'un conteneur recréé pendant 10 minutes
        HttpClient http = HttpClient.create()
                .resolver(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.min(props.timeoutMs(), 2000))
                .responseTimeout(Duration.ofMillis(props.timeoutMs()));
        this.web = builder.baseUrl(props.restUrl()).clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    public Mono<List<Map<String, Object>>> list(Map<String, Object> filtre, String correlationId) {
        return web.get().uri(u -> {
                    u.path("/patients");
                    if (filtre != null) {
                        filtre.forEach((k, v) -> { if (v != null) u.queryParam(k, v); });
                    }
                    return u.build();
                })
                .header("X-Correlation-Id", correlationId).retrieve().bodyToMono(LISTE);
    }

    /** Vide si le patient n'existe pas (404). */
    public Mono<Map<String, Object>> get(String id, String correlationId) {
        return web.get().uri("/patients/{id}", id).header("X-Correlation-Id", correlationId)
                .retrieve().bodyToMono(OBJET)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }

    public Mono<List<Map<String, Object>>> prescriptions(String id, String correlationId) {
        return web.get().uri("/patients/{id}/prescriptions", id).header("X-Correlation-Id", correlationId)
                .retrieve().bodyToMono(LISTE);
    }
}
