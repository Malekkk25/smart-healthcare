package tn.smarthealthcare.portal.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tn.smarthealthcare.portal.Dtos.Localisation;
import tn.smarthealthcare.portal.Dtos.UrgenceDemande;

class AccesEtValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void unPatientAccedeASesPropresDonnees() {
        assertDoesNotThrow(() -> Acces.verifierPatient("PATIENT", "PAT-000002", "PAT-000002"));
    }

    @Test
    void unPatientNeLitPasLesDonneesDUnAutre() {
        var e = assertThrows(ApiException.class, () -> Acces.verifierPatient("PATIENT", "PAT-000002", "PAT-000004"));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals("ACCES_REFUSE", e.code());
    }

    @Test
    void unPatientSansIdentiteEstRefuse() {
        assertThrows(ApiException.class, () -> Acces.verifierPatient("PATIENT", null, "PAT-000004"));
    }

    @Test
    void lePersonnelEtLesAppelsInternesSontAutorises() {
        assertDoesNotThrow(() -> Acces.verifierPatient("MEDECIN", null, "PAT-000004"));
        assertDoesNotThrow(() -> Acces.verifierPatient("ADMIN", null, "PAT-000004"));
        assertDoesNotThrow(() -> Acces.verifierPatient(null, null, "PAT-000004")); // appel service à service
    }

    private static UrgenceDemande demande(String patient, String alerte, String gravite, double lat) {
        return new UrgenceDemande(patient, alerte, gravite, new Localisation(lat, 10.18, null));
    }

    @Test
    void uneDemandeDUrgenceValideEstAcceptee() {
        assertTrue(validator.validate(demande("PAT-000004", "a1", "CRITIQUE", 36.8)).isEmpty());
    }

    @Test
    void lesGravitesInconnuesSontRefusees() {
        assertEquals(1, validator.validate(demande("PAT-000004", "a1", "URGENT", 36.8)).size());
    }

    @Test
    void unIdentifiantPatientMalFormeEstRefuse() {
        assertEquals(1, validator.validate(demande("oups", "a1", "CRITIQUE", 36.8)).size());
    }

    @Test
    void uneLatitudeHorsLimitesEstRefusee() {
        assertEquals(1, validator.validate(demande("PAT-000004", "a1", "CRITIQUE", 95)).size());
    }

    @Test
    void lIdentifiantDAlerteEstObligatoire() {
        assertEquals(1, validator.validate(demande("PAT-000004", " ", "CRITIQUE", 36.8)).size());
    }
}
