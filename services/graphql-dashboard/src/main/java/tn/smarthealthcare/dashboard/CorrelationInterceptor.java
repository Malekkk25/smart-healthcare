package tn.smarthealthcare.dashboard;

import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reprend X-Correlation-Id (ou en génère un), le met dans le contexte GraphQL et le renvoie dans la réponse. */
@Component
public class CorrelationInterceptor implements WebGraphQlInterceptor {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String received = request.getHeaders().getFirst(HEADER);
        String id = received == null || received.isBlank() ? UUID.randomUUID().toString() : received;
        request.configureExecutionInput((input, builder) -> builder.graphQLContext(Map.of("correlationId", id)).build());
        return chain.next(request).doOnNext(response -> response.getResponseHeaders().set(HEADER, id));
    }
}
