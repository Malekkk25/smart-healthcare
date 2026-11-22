package tn.smarthealthcare.portal.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.smarthealthcare.portal.Dtos.Ambulance;
import tn.smarthealthcare.portal.Dtos.Position;
import tn.smarthealthcare.portal.Dtos.UrgenceDemande;
import tn.smarthealthcare.portal.Dtos.UrgenceReponse;
import tn.smarthealthcare.portal.web.ApiException;

@Service
public class EmergencyService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyService.class);

    /** Vitesse moyenne simulée en ville (km/h) et marge d'occupation après arrivée (minutes). */
    private static final double VITESSE_KMH = 40.0;
    private static final int MARGE_OCCUPATION_MIN = 10;

    private final JdbcTemplate jdbc;
    private final PatientRepository patients;
    private final AppointmentService appointments;

    public EmergencyService(JdbcTemplate jdbc, PatientRepository patients, AppointmentService appointments) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.appointments = appointments;
    }

    // ---------------------------------------------------------------- ambulances

    @Transactional
    public List<Ambulance> listerAmbulances() {
        libererAmbulancesTerminees();
        return jdbc.query("SELECT id, statut, latitude, longitude FROM ambulance ORDER BY id",
                (rs, i) -> new Ambulance(rs.getString("id"), rs.getString("statut"),
                        new Position(rs.getDouble("latitude"), rs.getDouble("longitude"))));
    }

    private void libererAmbulancesTerminees() {
        jdbc.update("""
                UPDATE ambulance SET statut = 'DISPONIBLE', occupee_jusqua = NULL
                WHERE statut = 'EN_INTERVENTION' AND occupee_jusqua IS NOT NULL AND occupee_jusqua < now()""");
    }

    // ---------------------------------------------------------------- envoi d'urgence

    @Transactional
    public UrgenceReponse declencher(UrgenceDemande d) {
        // Sérialise les appels portant sur la même alerte (réessais de l'orchestrateur)
        jdbc.queryForList("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) t", d.alertId());

        UrgenceReponse existant = dispatchExistant(d.alertId());
        if (existant != null) {
            log.info("dispatch idempotent alertId={} -> {}", d.alertId(), existant.dispatchId());
            return existant;
        }

        patients.obtenir(d.patientId());
        libererAmbulancesTerminees();

        double lat = d.location().latitude();
        double lon = d.location().longitude();

        // Ambulance disponible la plus proche
        List<Object[]> candidats = jdbc.query(
                "SELECT id, latitude, longitude FROM ambulance WHERE statut = 'DISPONIBLE' FOR UPDATE",
                (rs, i) -> new Object[] {rs.getString("id"),
                        distanceKm(lat, lon, rs.getDouble("latitude"), rs.getDouble("longitude"))});
        Object[] meilleur = null;
        for (Object[] c : candidats) {
            if (meilleur == null || (double) c[1] < (double) meilleur[1]) {
                meilleur = c;
            }
        }

        String dispatchId = prochainIdDispatch();
        String ambulanceId = null;
        Integer eta = null;
        String rdvUrgence = null;

        if (meilleur != null) {
            ambulanceId = (String) meilleur[0];
            eta = Math.max(1, (int) Math.ceil((double) meilleur[1] / VITESSE_KMH * 60.0));
            jdbc.update("""
                    UPDATE ambulance SET statut = 'EN_INTERVENTION',
                           occupee_jusqua = now() + make_interval(mins => ?) WHERE id = ?""",
                    eta + MARGE_OCCUPATION_MIN, ambulanceId);
        } else {
            // Aucune ambulance : bascule sur un créneau d'urgence
            String slot = appointments.reserverCreneauUrgence();
            if (slot == null) {
                throw ApiException.conflit("AUCUNE_RESSOURCE_DISPONIBLE",
                        "Aucune ambulance disponible et aucun créneau d'urgence libre.");
            }
            rdvUrgence = appointments.creerRendezVousSurCreneauReserve(d.patientId(), slot,
                    "Urgence (alerte " + d.alertId() + ", gravité " + d.severity() + ")");
        }

        jdbc.update("""
                INSERT INTO dispatch (id, alert_id, patient_id, severity, latitude, longitude, ambulance_id,
                                      eta_minutes, fallback_appointment_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                dispatchId, d.alertId(), d.patientId(), d.severity(), lat, lon, ambulanceId, eta, rdvUrgence);

        log.info("dispatch {} alertId={} patient={} -> ambulance={} eta={} repli={}", dispatchId, d.alertId(),
                d.patientId(), ambulanceId, eta, rdvUrgence);
        return new UrgenceReponse(dispatchId, ambulanceId, eta, rdvUrgence);
    }

    private UrgenceReponse dispatchExistant(String alertId) {
        return jdbc.query("""
                        SELECT id, ambulance_id, eta_minutes, fallback_appointment_id FROM dispatch
                        WHERE alert_id = ?""",
                (rs, i) -> new UrgenceReponse(rs.getString("id"), rs.getString("ambulance_id"),
                        (Integer) rs.getObject("eta_minutes"), rs.getString("fallback_appointment_id")),
                alertId).stream().findFirst().orElse(null);
    }

    private String prochainIdDispatch() {
        Long seq = jdbc.queryForObject("SELECT nextval('dispatch_seq')", Long.class);
        return String.format("DSP-%d-%06d", java.time.Year.now(java.time.ZoneOffset.UTC).getValue(), seq);
    }

    /** Distance à vol d'oiseau (formule de haversine), en km. */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
