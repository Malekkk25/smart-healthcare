package tn.smarthealthcare.portal.web;

/**
 * Défense en profondeur : la Gateway valide le JWT et transmet X-User-Role / X-Patient-Id.
 * Un PATIENT ne peut accéder qu'à ses propres données. Sans en-têtes (appel interne), tout est autorisé.
 */
public final class Acces {

    private Acces() {}

    public static void verifierPatient(String role, String patientIdAppelant, String patientIdDemande) {
        if ("PATIENT".equals(role) && (patientIdAppelant == null || !patientIdAppelant.equals(patientIdDemande))) {
            throw ApiException.interdit("Un patient ne peut accéder qu'à ses propres données.");
        }
    }
}
