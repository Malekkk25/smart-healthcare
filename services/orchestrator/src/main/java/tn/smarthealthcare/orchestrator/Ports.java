package tn.smarthealthcare.orchestrator;

import java.util.Map;

/**
 * Interfaces vers les services appelés. Le moteur ne connaît que ces contrats : les vrais clients (SOAP, REST, GraphQL, IA)
 * les implémentent, et les tests utilisent des doublures.
 */
public final class Ports {

    private Ports() {}

    /** Panne (réseau, délai dépassé, 5xx) : on réessaie, puis on bascule sur le repli. */
    public static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Refus métier (4xx) : inutile de réessayer. */
    public static class RefusedException extends RuntimeException {
        public RefusedException(String message) {
            super(message);
        }
    }

    /** Plus aucune ressource disponible (409) : ni ambulance, ni créneau d'urgence. */
    public static class NoResourceException extends RefusedException {
        public NoResourceException(String message) {
            super(message);
        }
    }

    /**
     * Réponse de l'assurance. Un SOAP Fault métier (droits expirés, patient inconnu) est un RÉSULTAT, pas une panne :
     * fault vaut alors DroitsExpiresFault ou PatientInconnuFault.
     */
    public record DroitsOutcome(boolean ouverts, Integer taux, String motif, String fault, String faultMessage) {
        public boolean isFault() {
            return fault != null;
        }
    }

    public interface EhrPort {
        DroitsOutcome verifierDroits(String patientId, String codeActe, String correlationId);

        /** Nombre d'actes certifiés du patient (dossier). */
        int compterActes(String patientId, String correlationId);
    }

    public record DispatchOutcome(String dispatchId, String ambulanceId, Integer etaMinutes, String fallbackAppointmentId) {}

    public interface PortalPort {
        /** Idempotent : même alertId = même résultat. */
        DispatchOutcome dispatch(String alertId, String patientId, String severity, double latitude, double longitude,
                                 String correlationId);

        /** Repli : réserve un créneau d'urgence et renvoie l'identifiant du rendez-vous. */
        String bookEmergencyAppointment(String patientId, String alertId, String correlationId);

        /** Compensation : annule un rendez-vous. */
        void cancelAppointment(String appointmentId, String correlationId);
    }

    public interface DossierPort {
        /** Profil, dernières constantes, alertes ouvertes et traitement du patient (GraphQL, une requête). */
        Map<String, Object> fetch(String patientId, String correlationId);
    }

    public interface TriagePort {
        /** Score de risque et résumé de la fiche. */
        Map<String, Object> triage(Map<String, Object> payload, String correlationId);
    }
}
