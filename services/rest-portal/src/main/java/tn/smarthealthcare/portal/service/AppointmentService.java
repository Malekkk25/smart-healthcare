package tn.smarthealthcare.portal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.smarthealthcare.portal.Dtos.Creneau;
import tn.smarthealthcare.portal.Dtos.RendezVous;
import tn.smarthealthcare.portal.Dtos.RendezVousModification;
import tn.smarthealthcare.portal.web.ApiException;

@Service
public class AppointmentService {

    private final RowMapper<RendezVous> rdvMapper = (rs, i) -> new RendezVous(
            rs.getString("id"), rs.getString("patient_id"), rs.getString("slot_id"), rs.getString("doctor_id"),
            rs.getTimestamp("debut").toInstant(), rs.getString("motif"), rs.getString("statut"),
            "CONFIRME".equals(rs.getString("statut")) ? lienVisio(rs.getString("id")) : null);

    private static final String RDV_SQL = """
            SELECT r.id, r.patient_id, r.slot_id, c.doctor_id, c.debut, r.motif, r.statut
            FROM rendez_vous r JOIN creneau c ON c.id = r.slot_id""";

    private final JdbcTemplate jdbc;
    private final PatientRepository patients;

    private final String visioBaseUrl;
    private final String visioSel;

    public AppointmentService(JdbcTemplate jdbc, PatientRepository patients,
                              @Value("${app.visio.base-url:https://meet.jit.si}") String visioBaseUrl,
                              @Value("${app.visio.salt:smart-healthcare-dev}") String visioSel) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.visioBaseUrl = visioBaseUrl;
        this.visioSel = visioSel;
    }

    /**
     * Lien de téléconsultation Jitsi. Le nom de la salle dérive d'un hachage de l'identifiant du rendez-vous
     * et d'un sel secret : il n'est donc pas devinable à partir de « APT-2026-000451 ».
     */
    String lienVisio(String rendezVousId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((visioSel + rendezVousId).getBytes(StandardCharsets.UTF_8));
            return visioBaseUrl + "/SmartHealthcare-" + HexFormat.of().formatHex(h, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- créneaux

    public List<Creneau> creneauxLibres(String doctorId, LocalDate date) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, doctor_id, debut, fin, urgence FROM creneau WHERE reserve = false AND debut > now()");
        List<Object> args = new ArrayList<>();
        if (doctorId != null && !doctorId.isBlank()) {
            sql.append(" AND doctor_id = ?");
            args.add(doctorId);
        }
        if (date != null) {
            sql.append(" AND debut >= ? AND debut < ?");
            args.add(Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)));
            args.add(Timestamp.from(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        sql.append(" ORDER BY debut, doctor_id");
        return jdbc.query(sql.toString(),
                (rs, i) -> new Creneau(rs.getString("id"), rs.getString("doctor_id"),
                        rs.getTimestamp("debut").toInstant(), rs.getTimestamp("fin").toInstant(),
                        rs.getBoolean("urgence")),
                args.toArray());
    }

    // ---------------------------------------------------------------- rendez-vous

    public RendezVous obtenir(String id) {
        return jdbc.query(RDV_SQL + " WHERE r.id = ?", rdvMapper, id).stream().findFirst()
                .orElseThrow(() -> ApiException.introuvable("Aucun rendez-vous avec l'identifiant " + id + "."));
    }

    public List<RendezVous> listerParPatient(String patientId) {
        patients.obtenir(patientId);
        return jdbc.query(RDV_SQL + " WHERE r.patient_id = ? ORDER BY c.debut", rdvMapper, patientId);
    }

    @Transactional
    public RendezVous creer(String patientId, String slotId, String motif) {
        patients.obtenir(patientId);
        reserverCreneau(slotId);
        Long seq = jdbc.queryForObject("SELECT nextval('rdv_seq')", Long.class);
        String id = String.format("APT-%d-%06d", Year.now(ZoneOffset.UTC).getValue(), seq);
        jdbc.update("INSERT INTO rendez_vous (id, patient_id, slot_id, motif, statut) VALUES (?, ?, ?, ?, 'CONFIRME')",
                id, patientId, slotId, motif);
        return obtenir(id);
    }

    @Transactional
    public RendezVous modifier(String id, RendezVousModification m) {
        RendezVous actuel = obtenir(id);
        if (!"CONFIRME".equals(actuel.statut())) {
            throw ApiException.conflit("RENDEZ_VOUS_NON_MODIFIABLE",
                    "Le rendez-vous " + id + " est " + actuel.statut() + " et ne peut plus être modifié.");
        }
        if (m.slotId() != null && !m.slotId().equals(actuel.slotId())) {
            reserverCreneau(m.slotId());
            libererCreneau(actuel.slotId());
            jdbc.update("UPDATE rendez_vous SET slot_id = ? WHERE id = ?", m.slotId(), id);
        }
        if (m.motif() != null) {
            jdbc.update("UPDATE rendez_vous SET motif = ? WHERE id = ?", m.motif(), id);
        }
        return obtenir(id);
    }

    @Transactional
    public void annuler(String id) {
        RendezVous actuel = obtenir(id);
        if ("ANNULE".equals(actuel.statut())) {
            return; // idempotent
        }
        jdbc.update("UPDATE rendez_vous SET statut = 'ANNULE' WHERE id = ?", id);
        libererCreneau(actuel.slotId());
    }

    // ---------------------------------------------------------------- créneaux : réservation atomique

    private void reserverCreneau(String slotId) {
        int n = jdbc.update("UPDATE creneau SET reserve = true WHERE id = ? AND reserve = false AND debut > now()",
                slotId);
        if (n == 0) {
            Integer existe = jdbc.queryForObject("SELECT count(*) FROM creneau WHERE id = ?", Integer.class, slotId);
            if (existe == null || existe == 0) {
                throw ApiException.introuvable("Aucun créneau avec l'identifiant " + slotId + ".");
            }
            throw ApiException.conflit("CRENEAU_INDISPONIBLE",
                    "Le créneau " + slotId + " est déjà réservé ou passé.");
        }
    }

    private void libererCreneau(String slotId) {
        jdbc.update("UPDATE creneau SET reserve = false WHERE id = ?", slotId);
    }

    /** Premier créneau d'urgence libre (verrouillé), ou null. */
    public String reserverCreneauUrgence() {
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM creneau WHERE urgence = true AND reserve = false AND debut > ?
                ORDER BY debut LIMIT 1 FOR UPDATE SKIP LOCKED""", String.class, Timestamp.from(Instant.now()));
        if (ids.isEmpty()) {
            return null;
        }
        jdbc.update("UPDATE creneau SET reserve = true WHERE id = ?", ids.get(0));
        return ids.get(0);
    }

    /** Crée un rendez-vous sur un créneau déjà réservé par reserverCreneauUrgence. */
    public String creerRendezVousSurCreneauReserve(String patientId, String slotId, String motif) {
        Long seq = jdbc.queryForObject("SELECT nextval('rdv_seq')", Long.class);
        String id = String.format("APT-%d-%06d", Year.now(ZoneOffset.UTC).getValue(), seq);
        jdbc.update("INSERT INTO rendez_vous (id, patient_id, slot_id, motif, statut) VALUES (?, ?, ?, ?, 'CONFIRME')",
                id, patientId, slotId, motif);
        return id;
    }
}
