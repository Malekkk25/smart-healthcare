package tn.smarthealthcare.dashboard;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.AlertStatus;
import tn.smarthealthcare.telemetry.grpc.ListAlertsRequest;
import tn.smarthealthcare.telemetry.grpc.ListVitalsRequest;
import tn.smarthealthcare.telemetry.grpc.PatientRef;
import tn.smarthealthcare.telemetry.grpc.SubscribeRequest;
import tn.smarthealthcare.telemetry.grpc.TelemetryGrpc;

/** Client gRPC du service de télémétrie. Chaque appel porte x-correlation-id et un délai maximal. */
@Component
public class TelemetryClient implements DisposableBean {

    static final Metadata.Key<String> CORRELATION = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final TelemetryGrpc.TelemetryBlockingStub blocking;
    private final TelemetryGrpc.TelemetryStub async;
    private final int timeoutMs;

    public TelemetryClient(DashboardProperties props) {
        this.channel = ManagedChannelBuilder.forTarget(props.telemetryTarget()).usePlaintext().build();
        this.blocking = TelemetryGrpc.newBlockingStub(channel);
        this.async = TelemetryGrpc.newStub(channel);
        this.timeoutMs = props.timeoutMs();
    }

    private TelemetryGrpc.TelemetryBlockingStub stub(String correlationId) {
        Metadata headers = new Metadata();
        headers.put(CORRELATION, correlationId);
        return blocking.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    /** Vide si le patient n'a aucune mesure (NOT_FOUND). */
    public Mono<Map<String, Object>> latestVital(String patientId, String correlationId) {
        return Mono.fromCallable(() -> Mappers.vital(
                        stub(correlationId).getLatestVitals(PatientRef.newBuilder().setPatientId(patientId).build())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(StatusRuntimeException.class,
                        e -> e.getStatus().getCode() == Status.Code.NOT_FOUND ? Mono.empty() : Mono.error(e));
    }

    public Mono<List<Map<String, Object>>> vitals(String patientId, int limit, String correlationId) {
        return Mono.fromCallable(() -> stub(correlationId)
                        .listVitals(ListVitalsRequest.newBuilder().setPatientId(patientId).setLimit(limit).build())
                        .getSamplesList().stream().map(Mappers::vital).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** patientId vide ou null = tous ; status null = tous. */
    public Mono<List<Map<String, Object>>> alerts(String patientId, String status, int limit, String correlationId) {
        ListAlertsRequest.Builder b = ListAlertsRequest.newBuilder().setLimit(limit);
        if (patientId != null) b.setPatientId(patientId);
        if (status != null) b.setStatus(AlertStatus.valueOf(status));
        return Mono.fromCallable(() -> stub(correlationId).listAlerts(b.build())
                        .getAlertsList().stream().map(Mappers::alert).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Flux continu de toutes les alertes (SubscribeAlerts). */
    public void subscribeAlerts(StreamObserver<Alert> observer) {
        Metadata headers = new Metadata();
        headers.put(CORRELATION, "graphql-dashboard-subscription");
        async.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .subscribeAlerts(SubscribeRequest.getDefaultInstance(), observer);
    }

    @Override
    public void destroy() {
        channel.shutdown();
    }
}
