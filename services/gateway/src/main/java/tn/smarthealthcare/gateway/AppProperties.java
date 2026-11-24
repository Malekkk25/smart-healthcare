package tn.smarthealthcare.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration (préfixe "app") : mot de passe de démo, paramètres JWT et comptes de démonstration. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String demoPassword, Jwt jwt, RateLimit rateLimit, Ehr ehr, List<DemoUser> users) {

    public record Jwt(String secret, long ttlSeconds) {}

    /** Compte de service du SOAP (WS-Security) : la Gateway l'ajoute aux appels des utilisateurs authentifiés. Mot de passe vide = désactivé. */
    public record Ehr(String username, String password) {}

    /** Limites par minute et par adresse IP : API en général, et connexion en particulier. */
    public record RateLimit(int requestsPerMinute, int loginPerMinute) {}

    /** patientId n'est renseigné que pour le rôle PATIENT. */
    public record DemoUser(String username, String role, String patientId) {}
}
