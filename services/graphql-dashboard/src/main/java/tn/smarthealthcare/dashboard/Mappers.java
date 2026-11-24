package tn.smarthealthcare.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.VitalSample;

/** Messages gRPC -> objets du schéma GraphQL (mêmes noms de champs que contracts/dashboard.graphql). */
public final class Mappers {

    private Mappers() {}

    static String iso(com.google.protobuf.Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos()).toString();
    }

    public static Map<String, Object> vital(VitalSample v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measuredAt", iso(v.getMeasuredAt()));
        m.put("heartRate", v.getHeartRate());
        m.put("respRate", v.getRespRate());
        m.put("spo2", v.getSpo2());
        m.put("systolicBp", v.getSystolicBp());
        m.put("diastolicBp", v.getDiastolicBp());
        m.put("temperature", v.getTemperature());
        m.put("onOxygen", v.getOnOxygen());
        m.put("consciousness", v.getConsciousness().name());
        return m;
    }

    public static Map<String, Object> alert(Alert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getAlertId());
        m.put("patientId", a.getPatientId());
        m.put("raisedAt", iso(a.getRaisedAt()));
        m.put("severity", a.getSeverity().name());
        m.put("news2Score", a.getNews2Score());
        m.put("reason", a.getReason());
        m.put("trendWorsening", a.getTrendWorsening());
        m.put("status", a.getStatus().name());
        return m;
    }

    /** Rang de gravité pour filtrer (FAIBLE &lt; MOYENNE &lt; ELEVEE &lt; CRITIQUE). */
    public static int rank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "FAIBLE" -> 1;
            case "MOYENNE" -> 2;
            case "ELEVEE" -> 3;
            case "CRITIQUE" -> 4;
            default -> 0;
        };
    }
}
