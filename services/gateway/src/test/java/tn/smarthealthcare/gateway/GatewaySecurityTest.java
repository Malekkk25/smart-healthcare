package tn.smarthealthcare.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class GatewaySecurityTest {

    private static final String SECRET = "un-secret-de-test-d-au-moins-32-octets!!";

    private static JwtService jwt(String secret, long ttl) {
        return new JwtService(new AppProperties("x", new AppProperties.Jwt(secret, ttl),
                new AppProperties.RateLimit(100, 10), List.of()));
    }

    // ------------------------------------------------------------------ JWT

    @Test
    void unJetonEmisPuisVerifieConserveSesChamps() {
        var s = jwt(SECRET, 3600);
        var claims = s.verifier(s.emettre("patient1", "PATIENT", "PAT-000002"));
        assertEquals("patient1", claims.getSubject());
        assertEquals("PATIENT", claims.get("role", String.class));
        assertEquals("PAT-000002", claims.get("patientId", String.class));
        assertNotNull(claims.getExpiration());
    }

    @Test
    void lePersonnelNAPasDePatientId() {
        var s = jwt(SECRET, 3600);
        assertNull(s.verifier(s.emettre("medecin", "MEDECIN", null)).get("patientId"));
    }

    @Test
    void unJetonSigneAvecUnAutreSecretEstRefuse() {
        String jeton = jwt("un-autre-secret-de-test-aussi-long-32-octets", 3600).emettre("x", "ADMIN", null);
        assertThrows(JwtException.class, () -> jwt(SECRET, 3600).verifier(jeton));
    }

    @Test
    void unJetonExpireEstRefuse() {
        String jeton = jwt(SECRET, -10).emettre("x", "ADMIN", null);
        assertThrows(JwtException.class, () -> jwt(SECRET, 3600).verifier(jeton));
    }

    @Test
    void unJetonFalsifieEstRefuse() {
        String jeton = jwt(SECRET, 3600).emettre("patient1", "PATIENT", "PAT-000002");
        String[] p = jeton.split("\\.");
        String falsifie = p[0] + "." + p[1].substring(0, p[1].length() - 2) + "AA." + p[2];
        assertThrows(JwtException.class, () -> jwt(SECRET, 3600).verifier(falsifie));
    }

    // ------------------------------------------------------------------ matrice d'accès

    private final AccessRules rules = new AccessRules();

    @Test
    void laConnexionEstPublique() {
        assertTrue(rules.trouver(HttpMethod.POST, "/api/auth/login").rule().pub());
        assertTrue(rules.trouver(HttpMethod.GET, "/actuator/health").rule().pub());
    }

    @Test
    void leProfilEstProtegeParLIdentifiantDuPatient() {
        var m = rules.trouver(HttpMethod.GET, "/api/portal/patients/PAT-000002");
        assertEquals("id", m.rule().selfVar());
        assertEquals("PAT-000002", m.variables().get("id"));
        var o = rules.trouver(HttpMethod.GET, "/api/portal/patients/PAT-000002/prescriptions");
        assertEquals("PAT-000002", o.variables().get("id"));
    }

    @Test
    void laModificationDuProfilEstReserveAuPatientEtALAdmin() {
        assertEquals(Set.of("PATIENT", "ADMIN"), rules.trouver(HttpMethod.PUT, "/api/portal/patients/PAT-000002").rule().roles());
    }

    @Test
    void laListeDesPatientsEstReserveAuPersonnel() {
        assertEquals(Set.of("MEDECIN", "ADMIN"), rules.trouver(HttpMethod.GET, "/api/portal/patients").rule().roles());
    }

    @Test
    void ambulancesEtUrgencesSontReservees() {
        assertEquals(Set.of("MEDECIN", "ADMIN"), rules.trouver(HttpMethod.GET, "/api/portal/ambulances").rule().roles());
        assertEquals(Set.of("MEDECIN", "ADMIN"), rules.trouver(HttpMethod.POST, "/api/portal/emergency/dispatch").rule().roles());
    }

    @Test
    void lesRendezVousDUnPatientSontFiltresParSonIdentifiant() {
        assertEquals("patientId", rules.trouver(HttpMethod.GET, "/api/portal/appointments").rule().selfQuery());
    }

    @Test
    void lesServicesDeBSontReservesAuPersonnel() {
        assertEquals(Set.of("MEDECIN", "ADMIN"), rules.trouver(HttpMethod.POST, "/api/dashboard/graphql").rule().roles());
        assertEquals(Set.of("MEDECIN", "ADMIN"), rules.trouver(HttpMethod.GET, "/api/orchestrator/workflows/events").rule().roles());
    }

    @Test
    void leSoapEstOuvertATousLesUtilisateursAuthentifies() {
        assertTrue(rules.trouver(HttpMethod.POST, "/api/ehr").rule().roles().isEmpty());
        assertFalse(rules.trouver(HttpMethod.POST, "/api/ehr").rule().pub());
    }

    @Test
    void laVueFhirEstProtegeeParLIdentifiantDuPatient() {
        var m = rules.trouver(HttpMethod.GET, "/api/portal/fhir/Patient/PAT-000002");
        assertEquals("id", m.rule().selfVar());
        assertEquals("patient", rules.trouver(HttpMethod.GET, "/api/portal/fhir/Appointment").rule().selfQuery());
        assertEquals("patient", rules.trouver(HttpMethod.GET, "/api/portal/fhir/MedicationRequest").rule().selfQuery());
    }

    @Test
    void lesMetriquesPrometheusSontPubliques() {
        assertTrue(rules.trouver(HttpMethod.GET, "/actuator/prometheus").rule().pub());
    }

    @Test
    void uneRouteInconnueNAPasDeRegle() {
        assertNull(rules.trouver(HttpMethod.GET, "/api/inconnu"));
    }
}
