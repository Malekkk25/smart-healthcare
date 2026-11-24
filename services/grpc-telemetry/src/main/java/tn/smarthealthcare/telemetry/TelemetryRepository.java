package tn.smarthealthcare.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.AlertStatus;
import tn.smarthealthcare.telemetry.grpc.Consciousness;
import tn.smarthealthcare.telemetry.grpc.Severity;
import tn.smarthealthcare.telemetry.grpc.VitalSample;

/** Accès à telemetry_db (JDBC). Renvoie directement les messages du contrat gRPC. */
@Repository
public class TelemetryRepository {

    private final JdbcTemplate jdbc;

    public TelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ mesures

    public void insertSample(VitalSample s, int news2) {
        jdbc.update("""
                INSERT INTO vital_sample (patient_id, measured_at, heart_rate, resp_rate, spo2, systolic_bp, diastolic_bp,
                                          temperature, on_oxygen, consciousness, fall_detected, news2)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                s.getPatientId(), java.sql.Timestamp.from(toInstant(s.getMeasuredAt())), s.getHeartRate(), s.getRespRate(),
                s.getSpo2(), s.getSystolicBp(), s.getDiastolicBp(), s.getTemperature(), s.getOnOxygen(),
                s.getConsciousness().name(), s.getFallDetected(), news2);
    }

    public Optional<VitalSample> latest(String patientId) {
        return list(patientId, 1).stream().findFirst();
    }

    /** Du plus récent au plus ancien. */
    public List<VitalSample> list(String patientId, int limit) {
        return jdbc.query("""
                SELECT patient_id, measured_at, heart_rate, resp_rate, spo2, systolic_bp, diastolic_bp, temperature,
                       on_oxygen, consciousness, fall_detected
                FROM vital_sample WHERE patient_id = ? ORDER BY measured_at DESC, id DESC LIMIT ?""",
                (rs, i) -> VitalSample.newBuilder()
                        .setPatientId(rs.getString("patient_id"))
                        .setMeasuredAt(toTimestamp(rs.getTimestamp("measured_at").toInstant()))
                        .setHeartRate(rs.getInt("heart_rate")).setRespRate(rs.getInt("resp_rate"))
                        .setSpo2(rs.getDouble("spo2")).setSystolicBp(rs.getInt("systolic_bp"))
                        .setDiastolicBp(rs.getInt("diastolic_bp")).setTemperature(rs.getDouble("temperature"))
                        .setOnOxygen(rs.getBoolean("on_oxygen"))
                        .setConsciousness(Consciousness.valueOf(rs.getString("consciousness")))
                        .setFallDetected(rs.getBoolean("fall_detected")).build(),
                patientId, limit);
    }

    /** Scores NEWS2 des dernières mesures, du plus ancien au plus récent. */
    public List<Integer> recentScores(String patientId, int limit) {
        List<Integer> scores = new ArrayList<>(jdbc.queryForList(
                "SELECT news2 FROM vital_sample WHERE patient_id = ? ORDER BY measured_at DESC, id DESC LIMIT ?",
                Integer.class, patientId, limit));
        java.util.Collections.reverse(scores);
        return scores;
    }

    // ------------------------------------------------------------------ alertes

    public void insertAlert(Alert a) {
        jdbc.update("""
                INSERT INTO alert (id, patient_id, raised_at, severity, news2_score, reason, trend_worsening, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.fromString(a.getAlertId()), a.getPatientId(), java.sql.Timestamp.from(toInstant(a.getRaisedAt())),
                a.getSeverity().name(), a.getNews2Score(), a.getReason(), a.getTrendWorsening(), a.getStatus().name());
    }

    public Optional<Alert> findOpen(String patientId) {
        return list(patientId, AlertStatus.OPEN, 1).stream().findFirst();
    }

    public void setStatus(String alertId, AlertStatus status) {
        jdbc.update("UPDATE alert SET status = ? WHERE id = ?", status.name(), UUID.fromString(alertId));
    }

    /** Du plus récent au plus ancien. patientId vide = tous ; status UNSPECIFIED = tous. */
    public List<Alert> list(String patientId, AlertStatus status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, patient_id, raised_at, severity, news2_score, reason, trend_worsening, status
                FROM alert WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (patientId != null && !patientId.isBlank()) {
            sql.append(" AND patient_id = ?");
            args.add(patientId);
        }
        if (status != null && status != AlertStatus.STATUS_UNSPECIFIED && status != AlertStatus.UNRECOGNIZED) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY raised_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Alert.Builder b = Alert.newBuilder()
                    .setAlertId(rs.getString("id")).setPatientId(rs.getString("patient_id"))
                    .setRaisedAt(toTimestamp(rs.getTimestamp("raised_at").toInstant()))
                    .setSeverity(Severity.valueOf(rs.getString("severity")))
                    .setNews2Score(rs.getInt("news2_score"))
                    .setTrendWorsening(rs.getBoolean("trend_worsening"))
                    .setStatus(AlertStatus.valueOf(rs.getString("status")));
            if (rs.getString("reason") != null) b.setReason(rs.getString("reason"));
            return b.build();
        }, args.toArray());
    }

    // ------------------------------------------------------------------ conversions

    static Instant toInstant(com.google.protobuf.Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }

    static com.google.protobuf.Timestamp toTimestamp(Instant i) {
        return com.google.protobuf.Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }
}
