package tn.smarthealthcare.portal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Logique pure du service REST (sans base de données). */
class ServiceLogicTest {

    @Test
    void distanceUnDegreDeLatitudeVautEnviron111Km() {
        assertEquals(111.195, EmergencyService.distanceKm(0, 0, 1, 0), 0.01);
    }

    @Test
    void distanceNulleEntreDeuxPointsIdentiques() {
        assertEquals(0.0, EmergencyService.distanceKm(36.8065, 10.1815, 36.8065, 10.1815), 1e-9);
    }

    @Test
    void distanceEstSymetrique() {
        assertEquals(EmergencyService.distanceKm(36.8, 10.1, 36.9, 10.3), EmergencyService.distanceKm(36.9, 10.3, 36.8, 10.1), 1e-9);
    }

    @Test
    void distanceTunisSfaxEstPlausible() {
        double km = EmergencyService.distanceKm(36.8065, 10.1815, 34.7406, 10.7603); // à vol d'oiseau
        assertTrue(km > 220 && km < 250, "obtenu " + km);
    }

    private static AppointmentService service(String sel) {
        return new AppointmentService(null, null, "https://meet.jit.si", sel);
    }

    @Test
    void lienVisioEstStablePourUnMemeRendezVous() {
        assertEquals(service("sel").lienVisio("APT-2026-000001"), service("sel").lienVisio("APT-2026-000001"));
    }

    @Test
    void lienVisioNeRevelePasLIdentifiantDuRendezVous() {
        String lien = service("sel").lienVisio("APT-2026-000001");
        assertTrue(lien.startsWith("https://meet.jit.si/SmartHealthcare-"));
        assertTrue(!lien.contains("APT") && !lien.contains("000001"));
    }

    @Test
    void lienVisioDependDuRendezVousEtDuSel() {
        assertNotEquals(service("sel").lienVisio("APT-2026-000001"), service("sel").lienVisio("APT-2026-000002"));
        assertNotEquals(service("sel").lienVisio("APT-2026-000001"), service("autre").lienVisio("APT-2026-000001"));
    }
}
