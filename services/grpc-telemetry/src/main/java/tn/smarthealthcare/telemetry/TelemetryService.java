package tn.smarthealthcare.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tn.smarthealthcare.telemetry.AlertPolicy.Decision;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.AlertStatus;
import tn.smarthealthcare.telemetry.grpc.Consciousness;
import tn.smarthealthcare.telemetry.grpc.Severity;
import tn.smarthealthcare.telemetry.grpc.VitalSample;

/** Traite chaque mesure : validation, score NEWS2, stockage, décision d'alerte, dédoublonnage, diffusion. */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final Pattern PATIENT_ID = Pattern.compile("^PAT-\\d{6}$");
    /** Après ce nombre de mesures consécutives sans risque, l'alerte ouverte est fermée automatiquement. */
    static final int CALM_SAMPLES_TO_CLOSE = 3;
    static final int CALM_SCORE = 2;

    private final TelemetryRepository repo;
    private final AlertBus bus;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TelemetryService(TelemetryRepository repo, AlertBus bus, MeterRegistry metrics) {
        this.repo = repo;
        this.bus = bus;
        this.metrics = metrics;
    }

    /** @throws IllegalArgumentException si la mesure est invalide */
    public void process(VitalSample received) {
        validate(received);
        VitalSample s = received.hasMeasuredAt() && received.getMeasuredAt().getSeconds() > 0 ? received
                : received.toBuilder().setMeasuredAt(TelemetryRepository.toTimestamp(Instant.now())).build();

        News2.Vitals vitals = toVitals(s);
        News2.Result result = News2.score(vitals);

        synchronized (locks.computeIfAbsent(s.getPatientId(), k -> new Object())) {
            List<Integer> previous = repo.recentScores(s.getPatientId(), 10);
            repo.insertSample(s, result.total());
            metrics.counter("telemetry_samples_total", "result", "accepted").increment();

            Decision decision = AlertPolicy.evaluate(vitals, result, s.getFallDetected(), previous);
            Optional<Alert> open = repo.findOpen(s.getPatientId());

            if (decision.severity() != null) {
                Severity severity = Severity.valueOf(decision.severity().name());
                // Une seule alerte ouverte par patient : on n'en crée une autre que si la gravité monte
                if (open.isEmpty() || severity.getNumber() > open.get().getSeverity().getNumber()) {
                    open.ifPresent(o -> repo.setStatus(o.getAlertId(), AlertStatus.CLOSED));
                    Alert alert = Alert.newBuilder()
                            .setAlertId(UUID.randomUUID().toString()).setPatientId(s.getPatientId())
                            .setRaisedAt(TelemetryRepository.toTimestamp(Instant.now()))
                            .setSeverity(severity).setNews2Score(result.total()).setReason(decision.reason())
                            .setTrendWorsening(decision.trendWorsening()).setStatus(AlertStatus.OPEN)
                            .setVitals(s).build();
                    repo.insertAlert(alert);
                    metrics.counter("telemetry_alerts_total", "severity", severity.name()).increment();
                    log.info("alerte {} {} patient={} : {}", alert.getAlertId(), severity, s.getPatientId(), decision.reason());
                    bus.publish(alert);
                }
            } else if (open.isPresent() && isCalm(result.total(), previous)) {
                repo.setStatus(open.get().getAlertId(), AlertStatus.CLOSED);
                log.info("alerte {} fermée automatiquement (patient stabilisé)", open.get().getAlertId());
            }
        }
    }

    static boolean isCalm(int currentScore, List<Integer> previous) {
        if (currentScore > CALM_SCORE || previous.size() < CALM_SAMPLES_TO_CLOSE - 1) return false;
        return previous.subList(previous.size() - (CALM_SAMPLES_TO_CLOSE - 1), previous.size())
                .stream().allMatch(sc -> sc <= CALM_SCORE);
    }

    static News2.Vitals toVitals(VitalSample s) {
        boolean conscious = s.getConsciousness() == Consciousness.CONSCIOUSNESS_UNSPECIFIED
                || s.getConsciousness() == Consciousness.ALERT;
        return new News2.Vitals(s.getHeartRate(), s.getRespRate(), s.getSpo2(), s.getSystolicBp(),
                s.getTemperature(), s.getOnOxygen(), conscious);
    }

    static void validate(VitalSample s) {
        if (!PATIENT_ID.matcher(s.getPatientId()).matches()) throw new IllegalArgumentException("patient_id invalide : " + s.getPatientId());
        check("heart_rate", s.getHeartRate(), 0, 300);
        check("resp_rate", s.getRespRate(), 0, 80);
        check("spo2", s.getSpo2(), 0, 100);
        check("systolic_bp", s.getSystolicBp(), 0, 350);
        check("diastolic_bp", s.getDiastolicBp(), 0, 250);
        check("temperature", s.getTemperature(), 25, 45);
    }

    private static void check(String name, double value, double min, double max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " hors limites : " + value);
    }

    public void countRejected() {
        metrics.counter("telemetry_samples_total", "result", "rejected").increment();
    }
}
