package tn.smarthealthcare.orchestrator.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tn.smarthealthcare.orchestrator.WorkflowEngine.NotFoundException;

/** X-Correlation-Id repris dans les logs et renvoyé ; erreurs au format commun {code, message, correlationId}. */
public final class WebSupport {

    private WebSupport() {}

    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class CorrelationFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String id = req.getHeader("X-Correlation-Id");
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            MDC.put("correlationId", id);
            res.setHeader("X-Correlation-Id", id);
            try {
                chain.doFilter(req, res);
            } finally {
                MDC.remove("correlationId");
            }
        }
    }

    public record ApiError(String code, String message, String correlationId) {}

    @RestControllerAdvice
    public static class Errors {
        private static final Logger log = LoggerFactory.getLogger(Errors.class);

        private static ResponseEntity<ApiError> reply(HttpStatus status, String code, String message) {
            return ResponseEntity.status(status).body(new ApiError(code, message, MDC.get("correlationId")));
        }

        @ExceptionHandler(NotFoundException.class)
        ResponseEntity<ApiError> notFound(NotFoundException e) {
            return reply(HttpStatus.NOT_FOUND, "WORKFLOW_INTROUVABLE", e.getMessage());
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
            String detail = e.getBindingResult().getFieldErrors().stream()
                    .map(f -> "'" + f.getField() + "' : " + f.getDefaultMessage()).collect(Collectors.joining(" ; "));
            return reply(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Champs invalides - " + detail);
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
            return reply(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Corps de requête illisible ou invalide.");
        }

        @ExceptionHandler(NoResourceFoundException.class)
        ResponseEntity<ApiError> unknownRoute(NoResourceFoundException e) {
            return reply(HttpStatus.NOT_FOUND, "RESSOURCE_INTROUVABLE", "Route inconnue.");
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<ApiError> unexpected(Exception e) {
            log.error("Erreur inattendue", e);
            return reply(HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE", "Erreur interne du serveur.");
        }
    }
}
