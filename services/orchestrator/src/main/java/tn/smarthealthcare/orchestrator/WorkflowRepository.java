package tn.smarthealthcare.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tn.smarthealthcare.orchestrator.Model.Step;
import tn.smarthealthcare.orchestrator.Model.Workflow;

/** Accès à workflow_db : interventions, étapes, journal d'exécution et tâches à rejouer. */
@Repository
public class WorkflowRepository {

    public record Pending(long id, String workflowId, String kind, int attempts) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkflowRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ interventions

    /** Idempotence : vide si une intervention existe déjà pour cet alertId (clé unique en base). */
    public Optional<Workflow> createIfAbsent(String alertId, String patientId, String correlationId, String severity,
                                             int news2, String reason) {
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO workflow (id, alert_id, patient_id, correlation_id, status, started_at, severity, news2_score, reason)
                    VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)""",
                    id, alertId, patientId, correlationId, Timestamp.from(Instant.now()), severity, news2, reason);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        int ordre = 0;
        for (String name : Model.STEP_NAMES) {
            jdbc.update("INSERT INTO workflow_step (workflow_id, name, ordre, status, attempts) VALUES (?, ?, ?, 'PENDING', 0)",
                    id, name, ordre++);
        }
        return get(id);
    }

    public Optional<Workflow> get(String id) {
        return jdbc.query("SELECT * FROM workflow WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    public Optional<Workflow> findByAlertId(String alertId) {
        return jdbc.query("SELECT * FROM workflow WHERE alert_id = ?", (rs, i) -> map(rs), alertId).stream().findFirst();
    }

    public List<Workflow> list(String status, String patientId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM workflow WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status); }
        if (patientId != null && !patientId.isBlank()) { sql.append(" AND patient_id = ?"); args.add(patientId); }
        sql.append(" ORDER BY started_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
    }

    private Workflow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String id = rs.getString("id");
        List<Step> steps = jdbc.query(
                "SELECT name, status, attempts, detail FROM workflow_step WHERE workflow_id = ? ORDER BY ordre",
                (r, i) -> new Step(r.getString("name"), r.getString("status"), r.getInt("attempts"), r.getString("detail")), id);
        Timestamp ended = rs.getTimestamp("ended_at");
        return new Workflow(id, rs.getString("alert_id"), rs.getString("patient_id"), rs.getString("correlation_id"),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(), ended == null ? null : ended.toInstant(),
                rs.getString("admin_alert"), steps, rs.getString("severity"), (Integer) rs.getObject("news2_score"),
                rs.getString("reason"), rs.getString("dispatch_id"), rs.getString("ambulance_id"),
                (Integer) rs.getObject("eta_minutes"), rs.getString("appointment_id"), readDossier(rs.getString("dossier")),
                HomeLocation.of(rs.getString("patient_id"))[0], HomeLocation.of(rs.getString("patient_id"))[1]);
    }

    private Map<String, Object> readDossier(String text) {
        if (text == null) return null;
        try {
            return json.readValue(text, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ mises à jour

    public void updateStep(String workflowId, String name, String status, int attempts, String detail) {
        jdbc.update("UPDATE workflow_step SET status = ?, attempts = ?, detail = ? WHERE workflow_id = ? AND name = ?",
                status, attempts, detail, workflowId, name);
        jdbc.update("INSERT INTO step_log (workflow_id, step, status, attempt, detail) VALUES (?, ?, ?, ?, ?)",
                workflowId, name, status, attempts, detail);
    }

    public void setStatus(String workflowId, String status, Instant endedAt) {
        jdbc.update("UPDATE workflow SET status = ?, ended_at = ? WHERE id = ?", status,
                endedAt == null ? null : Timestamp.from(endedAt), workflowId);
    }

    public void appendAdminAlert(String workflowId, String message) {
        String actuel = jdbc.queryForObject("SELECT admin_alert FROM workflow WHERE id = ?", String.class, workflowId);
        jdbc.update("UPDATE workflow SET admin_alert = ? WHERE id = ?",
                actuel == null || actuel.isBlank() ? message : actuel + " | " + message, workflowId);
    }

    public void setDispatch(String workflowId, String dispatchId, String ambulanceId, Integer eta, String appointmentId) {
        jdbc.update("UPDATE workflow SET dispatch_id = ?, ambulance_id = ?, eta_minutes = ?, appointment_id = ? WHERE id = ?",
                dispatchId, ambulanceId, eta, appointmentId, workflowId);
    }

    public void setDossier(String workflowId, Map<String, Object> dossier) {
        try {
            jdbc.update("UPDATE workflow SET dossier = ? WHERE id = ?", json.writeValueAsString(dossier), workflowId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> journal(String workflowId) {
        return jdbc.queryForList("SELECT step, status, attempt, detail, at FROM step_log WHERE workflow_id = ? ORDER BY id", workflowId);
    }

    // ------------------------------------------------------------------ tâches à rejouer

    public void addPending(String workflowId, String kind, long delaySeconds) {
        jdbc.update("INSERT INTO pending_task (workflow_id, kind, attempts, next_run, status) VALUES (?, ?, 0, ?, 'PENDING')",
                workflowId, kind, Timestamp.from(Instant.now().plusSeconds(delaySeconds)));
    }

    public List<Pending> duePending(Instant now, int limit) {
        return jdbc.query("SELECT id, workflow_id, kind, attempts FROM pending_task WHERE status = 'PENDING' AND next_run <= ? ORDER BY next_run LIMIT ?",
                (rs, i) -> new Pending(rs.getLong("id"), rs.getString("workflow_id"), rs.getString("kind"), rs.getInt("attempts")),
                Timestamp.from(now), limit);
    }

    public int pendingCount(String workflowId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pending_task WHERE workflow_id = ? AND status = 'PENDING'", Integer.class, workflowId);
        return n == null ? 0 : n;
    }

    public void finishPending(long id, String status) {
        jdbc.update("UPDATE pending_task SET status = ? WHERE id = ?", status, id);
    }

    public void reschedule(long id, int attempts, long delaySeconds) {
        jdbc.update("UPDATE pending_task SET attempts = ?, next_run = ? WHERE id = ?", attempts,
                Timestamp.from(Instant.now().plusSeconds(delaySeconds)), id);
    }

    public void cancelPending(String workflowId) {
        jdbc.update("UPDATE pending_task SET status = 'CANCELLED' WHERE workflow_id = ? AND status = 'PENDING'", workflowId);
    }
}
