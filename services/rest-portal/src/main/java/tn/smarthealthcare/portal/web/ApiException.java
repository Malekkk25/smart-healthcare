package tn.smarthealthcare.portal.web;

import org.springframework.http.HttpStatus;

/** Erreur métier convertie en réponse JSON {code, message, correlationId}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException introuvable(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESSOURCE_INTROUVABLE", message);
    }

    public static ApiException conflit(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException invalide(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "REQUETE_INVALIDE", message);
    }

    public static ApiException interdit(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCES_REFUSE", message);
    }
}
