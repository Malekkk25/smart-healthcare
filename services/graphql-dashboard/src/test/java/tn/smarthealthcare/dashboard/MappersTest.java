package tn.smarthealthcare.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.AlertStatus;
import tn.smarthealthcare.telemetry.grpc.Consciousness;
import tn.smarthealthcare.telemetry.grpc.Severity;
import tn.smarthealthcare.telemetry.grpc.VitalSample;

class MappersTest {

    @Test
    void uneAlerteGrpcDevientUnObjetGraphQL() {
        Alert a = Alert.newBuilder().setAlertId("a1").setPatientId("PAT-000004")
                .setRaisedAt(Timestamp.newBuilder().setSeconds(1_700_000_000)).setSeverity(Severity.CRITIQUE)
                .setNews2Score(9).setReason("NEWS2 = 9").setTrendWorsening(true).setStatus(AlertStatus.OPEN).build();
        var m = Mappers.alert(a);
        assertEquals("a1", m.get("id"));
        assertEquals("CRITIQUE", m.get("severity"));
        assertEquals("OPEN", m.get("status"));
        assertEquals(9, m.get("news2Score"));
        assertEquals("2023-11-14T22:13:20Z", m.get("raisedAt"));
    }

    @Test
    void uneMesureGrpcDevientUnObjetGraphQL() {
        var v = VitalSample.newBuilder().setPatientId("PAT-000004").setHeartRate(148).setSpo2(90.5)
                .setConsciousness(Consciousness.VOICE).setOnOxygen(true).build();
        var m = Mappers.vital(v);
        assertEquals(148, m.get("heartRate"));
        assertEquals(90.5, m.get("spo2"));
        assertEquals("VOICE", m.get("consciousness"));
        assertEquals(true, m.get("onOxygen"));
    }

    @Test
    void ordreDeGravite() {
        assertTrue(Mappers.rank("FAIBLE") < Mappers.rank("MOYENNE"));
        assertTrue(Mappers.rank("MOYENNE") < Mappers.rank("ELEVEE"));
        assertTrue(Mappers.rank("ELEVEE") < Mappers.rank("CRITIQUE"));
        assertEquals(0, Mappers.rank(null));
    }
}
