package tn.smarthealthcare.gateway;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Matrice d'accès de la Gateway (voir contracts/conventions.md, section 4). La première règle qui correspond
 * s'applique. Un jeu de rôles vide signifie « tout utilisateur authentifié ».
 */
@Component
public class AccessRules {

    public static final String PATIENT = "PATIENT";
    public static final String MEDECIN = "MEDECIN";
    public static final String ADMIN = "ADMIN";

    /**
     * @param methods  méthodes concernées (vide = toutes)
     * @param pub      route publique (aucun jeton exigé)
     * @param selfVar  variable de chemin qui doit être égale au patientId du jeton pour un PATIENT
     * @param selfQuery paramètre de requête qui doit être égal au patientId du jeton pour un PATIENT
     */
    public record Rule(Set<HttpMethod> methods, PathPattern pattern, Set<String> roles, boolean pub, String selfVar,
                       String selfQuery) {}

    public record Match(Rule rule, Map<String, String> variables) {}

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final Set<String> TOUS = Set.of();
    private static final Set<String> STAFF = Set.of(MEDECIN, ADMIN);
    private static final Set<String> PATIENT_OU_ADMIN = Set.of(PATIENT, ADMIN);

    private final List<Rule> rules = List.of(
            publique(HttpMethod.POST, "/api/auth/login"),
            publique(HttpMethod.GET, "/actuator/health"),
            publique(HttpMethod.GET, "/actuator/prometheus"), // métriques, lues par Prometheus

            // Portail REST : vue FHIR en lecture seule (un patient ne lit que ses propres ressources)
            rule(Set.of(HttpMethod.GET), "/api/portal/fhir/Patient/{id}", TOUS, "id", null),
            rule(Set.of(HttpMethod.GET), "/api/portal/fhir/Appointment", TOUS, null, "patient"),
            rule(Set.of(HttpMethod.GET), "/api/portal/fhir/MedicationRequest", TOUS, null, "patient"),

            // Portail REST
            rule(Set.of(HttpMethod.GET), "/api/portal/patients", STAFF, null, null),
            rule(Set.of(HttpMethod.PUT), "/api/portal/patients/{id}", PATIENT_OU_ADMIN, "id", null),
            rule(Set.of(), "/api/portal/patients/{id}", TOUS, "id", null),
            rule(Set.of(), "/api/portal/patients/{id}/**", TOUS, "id", null),
            rule(Set.of(HttpMethod.GET), "/api/portal/appointments", TOUS, null, "patientId"),
            rule(Set.of(), "/api/portal/appointments/**", TOUS, null, null),
            rule(Set.of(), "/api/portal/slots/**", TOUS, null, null),
            rule(Set.of(), "/api/portal/ambulances/**", STAFF, null, null),
            rule(Set.of(), "/api/portal/emergency/**", STAFF, null, null),

            // SOAP (simple relais) : tout utilisateur authentifié
            rule(Set.of(), "/api/ehr/**", TOUS, null, null),

            // Services de B
            rule(Set.of(), "/api/dashboard/**", STAFF, null, null),
            rule(Set.of(), "/api/orchestrator/**", STAFF, null, null));

    private static Rule rule(Set<HttpMethod> methods, String pattern, Set<String> roles, String selfVar,
                             String selfQuery) {
        return new Rule(methods, PARSER.parse(pattern), roles, false, selfVar, selfQuery);
    }

    private static Rule publique(HttpMethod method, String pattern) {
        return new Rule(Set.of(method), PARSER.parse(pattern), Set.of(), true, null, null);
    }

    /** @return la règle correspondante, ou null si la route n'est pas déclarée */
    public Match trouver(HttpMethod method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (Rule r : rules) {
            if (!r.methods().isEmpty() && !r.methods().contains(method)) {
                continue;
            }
            var info = r.pattern().matchAndExtract(container);
            if (info != null) {
                return new Match(r, info.getUriVariables());
            }
        }
        return null;
    }
}
