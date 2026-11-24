package tn.smarthealthcare.dashboard;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Erreurs lisibles et ciblées : le champ en panne échoue, le reste de la réponse est conservé. */
@Component
public class DashboardErrorResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof StatusRuntimeException) {
            return GraphqlErrorBuilder.newError(env).errorType(ErrorType.INTERNAL_ERROR)
                    .message("Service de télémétrie indisponible")
                    .extensions(Map.of("code", "TELEMETRIE_INDISPONIBLE")).build();
        }
        if (ex instanceof WebClientRequestException || ex instanceof WebClientResponseException) {
            return GraphqlErrorBuilder.newError(env).errorType(ErrorType.INTERNAL_ERROR)
                    .message("Service REST du portail indisponible")
                    .extensions(Map.of("code", "PORTAIL_INDISPONIBLE")).build();
        }
        return null; // traitement par défaut (INTERNAL_ERROR)
    }

    /** Erreur sans environnement de résolution (utilisée avec DataFetcherResult). */
    static GraphQLError erreur(String message, String code) {
        return GraphqlErrorBuilder.newError().errorType(ErrorType.INTERNAL_ERROR).message(message)
                .extensions(Map.of("code", code)).build();
    }
}
