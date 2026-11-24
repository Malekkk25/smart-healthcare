package tn.smarthealthcare.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.protobuf.Timestamp;
import io.grpc.stub.ServerCallStreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.AlertStatus;
import tn.smarthealthcare.telemetry.grpc.Consciousness;
import tn.smarthealthcare.telemetry.grpc.Severity;
import tn.smarthealthcare.telemetry.grpc.SubscribeRequest;
import tn.smarthealthcare.telemetry.grpc.VitalSample;

/** Chaîne complète (validation, NEWS2, stockage, dédoublonnage, diffusion) sur une base H2 en mémoire. */
class TelemetryServiceTest {

    private TelemetryRepository repo;
    private TelemetryService service;
    private AlertBus bus;
    private ServerCallStreamObserver<Alert> abonne;
    private long t;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void init() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:tel" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new JdbcTemplate(ds).execute("CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        repo = new TelemetryRepository(new JdbcTemplate(ds));
        bus = new AlertBus();
        service = new TelemetryService(repo, bus, new SimpleMeterRegistry());
        abonne = mock(ServerCallStreamObserver.class);
        bus.subscribe(SubscribeRequest.getDefaultInstance(), abonne);
        t = Instant.now().getEpochSecond() - 1000;
    }

    private VitalSample.Builder mesure(String patient) {
        t += 1;
        return VitalSample.newBuilder().setPatientId(patient)
                .setMeasuredAt(Timestamp.newBuilder().setSeconds(t))
                .setHeartRate(70).setRespRate(16).setSpo2(98).setSystolicBp(120).setDiastolicBp(80)
                .setTemperature(36.8).setConsciousness(Consciousness.ALERT);
    }

    private VitalSample.Builder critique(String patient) {
        return mesure(patient).setHeartRate(148).setRespRate(27).setSpo2(90);
    }

    @Test
    void desMesuresNormalesNeDeclenchentAucuneAlerte() {
        for (int i = 0; i < 5; i++) service.process(mesure("PAT-000004").build());
        verify(abonne, never()).onNext(any());
        assertTrue(repo.list("PAT-000004", AlertStatus.STATUS_UNSPECIFIED, 10).isEmpty());
        assertEquals(5, repo.list("PAT-000004", 100).size());
    }

    @Test
    void uneMesureCritiqueCreeUneAlerteDiffusee() {
        service.process(critique("PAT-000004").build());
        ArgumentCaptor<Alert> c = ArgumentCaptor.forClass(Alert.class);
        verify(abonne, times(1)).onNext(c.capture());
        Alert a = c.getValue();
        assertEquals(Severity.CRITIQUE, a.getSeverity());
        assertEquals("PAT-000004", a.getPatientId());
        assertEquals(9, a.getNews2Score());
        assertEquals(AlertStatus.OPEN, a.getStatus());
        assertTrue(a.getAlertId().matches("[0-9a-f-]{36}"));
        assertEquals(148, a.getVitals().getHeartRate());
    }

    @Test
    void deuxMesuresCritiquesConsecutivesNeFontQuUneAlerte() {
        service.process(critique("PAT-000004").build());
        service.process(critique("PAT-000004").build());
        verify(abonne, times(1)).onNext(any());
        assertEquals(1, repo.list("PAT-000004", AlertStatus.OPEN, 10).size());
    }

    @Test
    void uneAggravationCreeUneNouvelleAlerteEtFermeLAncienne() {
        // ELEVEE : FR 22 (2) + pouls 115 (2) + SpO2 94 (1) = 5
        service.process(mesure("PAT-000004").setRespRate(22).setHeartRate(115).setSpo2(94).build());
        service.process(critique("PAT-000004").build()); // CRITIQUE
        verify(abonne, times(2)).onNext(any());
        var ouvertes = repo.list("PAT-000004", AlertStatus.OPEN, 10);
        assertEquals(1, ouvertes.size());
        assertEquals(Severity.CRITIQUE, ouvertes.get(0).getSeverity());
        assertEquals(1, repo.list("PAT-000004", AlertStatus.CLOSED, 10).size());
    }

    @Test
    void unPatientStabiliseVoitSonAlerteFermee() {
        service.process(critique("PAT-000004").build());
        assertEquals(1, repo.list("PAT-000004", AlertStatus.OPEN, 10).size());
        for (int i = 0; i < TelemetryService.CALM_SAMPLES_TO_CLOSE; i++) service.process(mesure("PAT-000004").build());
        assertTrue(repo.list("PAT-000004", AlertStatus.OPEN, 10).isEmpty());
        assertEquals(1, repo.list("PAT-000004", AlertStatus.CLOSED, 10).size());
    }

    @Test
    void lesPatientsSontIndependants() {
        service.process(critique("PAT-000004").build());
        service.process(mesure("PAT-000002").build());
        assertEquals(1, repo.list("", AlertStatus.OPEN, 10).size());
        assertEquals(1, repo.latest("PAT-000002").map(v -> 1).orElse(0));
    }

    @Test
    void ladegradationProgressiveEstDetectee() {
        for (int i = 0; i < 4; i++) service.process(mesure("PAT-000012").build());
        service.process(mesure("PAT-000012").setRespRate(22).setTemperature(38.5).build()); // total 3
        var alertes = repo.list("PAT-000012", AlertStatus.OPEN, 10);
        assertEquals(1, alertes.size());
        assertEquals(Severity.MOYENNE, alertes.get(0).getSeverity());
        assertTrue(alertes.get(0).getTrendWorsening());
    }

    @Test
    void lAbonnementFiltreParGraviteMinimale() {
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<Alert> exigeant = mock(ServerCallStreamObserver.class);
        bus.subscribe(SubscribeRequest.newBuilder().setMinSeverity(Severity.CRITIQUE).build(), exigeant);
        service.process(mesure("PAT-000004").setRespRate(22).setHeartRate(115).setSpo2(94).build()); // ELEVEE
        verify(exigeant, never()).onNext(any());
        service.process(critique("PAT-000004").build());
        verify(exigeant, times(1)).onNext(any());
    }

    @Test
    void lAbonnementFiltreParPatient() {
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<Alert> cible = mock(ServerCallStreamObserver.class);
        bus.subscribe(SubscribeRequest.newBuilder().addPatientIds("PAT-000099").build(), cible);
        service.process(critique("PAT-000004").build());
        verify(cible, never()).onNext(any());
    }

    @Test
    void desMesuresInvalidesSontRefusees() {
        assertThrows(IllegalArgumentException.class, () -> service.process(mesure("oups").build()));
        assertThrows(IllegalArgumentException.class, () -> service.process(mesure("PAT-000004").setSpo2(140).build()));
        assertThrows(IllegalArgumentException.class, () -> service.process(mesure("PAT-000004").setTemperature(10).build()));
        assertTrue(repo.list("PAT-000004", 10).isEmpty());
    }

    @Test
    void lesDernieresConstantesSontLuesDuPlusRecent() {
        service.process(mesure("PAT-000004").setHeartRate(60).build());
        service.process(mesure("PAT-000004").setHeartRate(61).build());
        service.process(mesure("PAT-000004").setHeartRate(62).build());
        assertEquals(62, repo.latest("PAT-000004").orElseThrow().getHeartRate());
        List<VitalSample> deux = repo.list("PAT-000004", 2);
        assertEquals(List.of(62, 61), deux.stream().map(VitalSample::getHeartRate).toList());
    }
}
