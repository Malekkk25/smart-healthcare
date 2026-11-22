package tn.smarthealthcare.portal.web;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Toutes les erreurs suivent le schéma commun {code, message, correlationId}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String code, String message, String correlationId) {}

    private static ResponseEntity<ApiError> reponse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> metier(ApiException e) {
        return reponse(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> "'" + f.getField() + "' : " + f.getDefaultMessage())
                .collect(Collectors.joining(" ; "));
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Champs invalides - " + detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> contraintes(ConstraintViolationException e) {
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> corpsIllisible(HttpMessageNotReadableException e) {
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", "Corps de requête illisible ou invalide.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> parametreManquant(MissingServletRequestParameterException e) {
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                "Le paramètre '" + e.getParameterName() + "' est obligatoire.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> enteteManquant(MissingRequestHeaderException e) {
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                "L'en-tête '" + e.getHeaderName() + "' est obligatoire.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeInvalide(MethodArgumentTypeMismatchException e) {
        return reponse(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE",
                "Valeur invalide pour le paramètre '" + e.getName() + "'.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methode(HttpRequestMethodNotSupportedException e) {
        return reponse(HttpStatus.METHOD_NOT_ALLOWED, "METHODE_NON_AUTORISEE", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> routeInconnue(NoResourceFoundException e) {
        return reponse(HttpStatus.NOT_FOUND, "RESSOURCE_INTROUVABLE", "Route inconnue.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> inattendue(Exception e) {
        log.error("Erreur inattendue", e);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE", "Erreur interne du serveur.");
    }
}
