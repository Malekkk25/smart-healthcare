package tn.smarthealthcare.orchestrator;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.SubscribeRequest;
import tn.smarthealthcare.telemetry.grpc.TelemetryGrpc;

/**
 * Étape 1 du workflow : écoute les alertes du service gRPC (SubscribeAlerts) et lance une intervention pour chaque alerte
 * dont la gravité atteint le seuil (CRITIQUE par défaut). Se reconnecte tout seul si la télémétrie redémarre.
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);
    private static final Metadata.Key<String> CORRELATION = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private final WorkflowEngine engine;
    private final ManagedChannel channel;
    private final int minRank;
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean stopped;

    public AlertListener(WorkflowEngine engine, OrchestratorProperties props) {
        this.engine = engine;
        this.channel = ManagedChannelBuilder.forTarget(props.telemetryTarget()).usePlaintext().build();
        this.minRank = rank(props.triggerSeverity());
    }

    static int rank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "FAIBLE" -> 1;
            case "MOYENNE" -> 2;
            case "ELEVEE" -> 3;
            case "CRITIQUE" -> 4;
            default -> 4;
        };
    }

    @PostConstruct
    void start() {
        connect();
    }

    private void connect() {
        if (stopped) return;
        Metadata headers = new Metadata();
        headers.put(CORRELATION, "orchestrator-subscription");
        try {
            TelemetryGrpc.newStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .subscribeAlerts(SubscribeRequest.getDefaultInstance(), new StreamObserver<>() {
                        @Override
                        public void onNext(Alert alert) {
                            onAlert(alert);
                        }

                        @Override
                        public void onError(Throwable t) {
                            log.warn("abonnement aux alertes interrompu ({}), nouvel essai dans 3 s", t.getMessage());
                            reconnectLater();
                        }

                        @Override
                        public void onCompleted() {
                            reconnectLater();
                        }
                    });
            log.info("abonné aux alertes du service de télémétrie");
        } catch (RuntimeException e) {
            log.warn("abonnement impossible : {}", e.toString());
            reconnectLater();
        }
    }

    void onAlert(Alert alert) {
        if (alert.getSeverity().getNumber() < minRank) {
            log.debug("alerte {} ignorée (gravité {} sous le seuil)", alert.getAlertId(), alert.getSeverity());
            return;
        }
        log.info("alerte {} {} patient={} : démarrage du workflow", alert.getAlertId(), alert.getSeverity(), alert.getPatientId());
        try {
            engine.start(alert.getAlertId(), alert.getPatientId(), alert.getSeverity().name(), alert.getNews2Score(), alert.getReason());
        } catch (RuntimeException e) {
            log.error("impossible de démarrer le workflow de l'alerte {}", alert.getAlertId(), e);
        }
    }

    private void reconnectLater() {
        if (!stopped) retry.schedule(this::connect, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        stopped = true;
        retry.shutdownNow();
        channel.shutdown();
    }
}
