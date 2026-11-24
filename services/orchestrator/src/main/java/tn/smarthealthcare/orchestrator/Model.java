package tn.smarthealthcare.orchestrator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Objets de contracts/workflow-api.md (plus quelques champs facultatifs : intervention et fiche). */
public final class Model {

    private Model() {}

    public static final String ALERTE = "ALERTE";
    public static final String DROITS = "DROITS";
    public static final String PRISE_EN_CHARGE = "PRISE_EN_CHARGE";
    public static final String FICHE = "FICHE";
    public static final List<String> STEP_NAMES = List.of(ALERTE, DROITS, PRISE_EN_CHARGE, FICHE);

    /** Statuts d'étape : PENDING, RUNNING, SUCCEEDED, DEGRADED (repli utilisé), FAILED. */
    public record Step(String name, String status, int attempts, String detail) {}

    /**
     * Statuts : RUNNING, COMPLETED, DEGRADED, FAILED.
     * dossier = fiche d'intervention (profil, constantes, alertes, traitement, résumé IA), absente tant qu'elle n'est pas prête.
     */
    public record Workflow(
            String id,
            String alertId,
            String patientId,
            String correlationId,
            String status,
            Instant startedAt,
            Instant endedAt,
            String adminAlert,
            List<Step> steps,
            String severity,
            Integer news2Score,
            String reason,
            String dispatchId,
            String ambulanceId,
            Integer etaMinutes,
            String appointmentId,
            Map<String, Object> dossier) {}

    /** Statut global calculé à partir des étapes. */
    public static String computeStatus(List<Step> steps) {
        if (steps.stream().anyMatch(s -> s.status().equals("FAILED"))) return "FAILED";
        if (steps.stream().anyMatch(s -> s.status().equals("PENDING") || s.status().equals("RUNNING"))) return "RUNNING";
        if (steps.stream().anyMatch(s -> s.status().equals("DEGRADED"))) return "DEGRADED";
        return "COMPLETED";
    }
}
