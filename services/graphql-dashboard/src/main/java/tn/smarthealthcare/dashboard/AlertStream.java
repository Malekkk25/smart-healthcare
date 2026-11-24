package tn.smarthealthcare.dashboard;

import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tn.smarthealthcare.telemetry.grpc.Alert;

/**
 * Un seul abonnement gRPC (SubscribeAlerts) partagé par tous les clients GraphQL.
 * Se reconnecte tout seul si la télémétrie redémarre.
 */
@Component
public class AlertStream {

    private static final Logger log = LoggerFactory.getLogger(AlertStream.class);
    private static final Duration RECONNECT = Duration.ofSeconds(3);

    private final TelemetryClient telemetry;
    private final Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().directBestEffort();

    public AlertStream(TelemetryClient telemetry) {
        this.telemetry = telemetry;
    }

    @PostConstruct
    void start() {
        connect();
    }

    private void connect() {
        try {
            telemetry.subscribeAlerts(new StreamObserver<>() {
                @Override
                public void onNext(Alert alert) {
                    log.info("alerte reçue {} {} patient={}", alert.getAlertId(), alert.getSeverity(), alert.getPatientId());
                    sink.tryEmitNext(Mappers.alert(alert));
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("abonnement aux alertes interrompu ({}), nouvel essai dans {} s", t.getMessage(), RECONNECT.toSeconds());
                    reconnectLater();
                }

                @Override
                public void onCompleted() {
                    reconnectLater();
                }
            });
        } catch (RuntimeException e) {
            log.warn("abonnement aux alertes impossible : {}", e.toString());
            reconnectLater();
        }
    }

    private void reconnectLater() {
        Mono.delay(RECONNECT).subscribe(x -> connect());
    }

    /** Alertes créées à partir de maintenant, de gravité au moins égale à minSeverity. */
    public Flux<Map<String, Object>> alerts(String minSeverity) {
        int min = Mappers.rank(minSeverity);
        return sink.asFlux().filter(a -> Mappers.rank((String) a.get("severity")) >= min);
    }
}
