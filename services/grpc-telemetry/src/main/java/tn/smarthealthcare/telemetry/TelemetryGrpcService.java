package tn.smarthealthcare.telemetry;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.AlertList;
import tn.smarthealthcare.telemetry.grpc.ListAlertsRequest;
import tn.smarthealthcare.telemetry.grpc.ListVitalsRequest;
import tn.smarthealthcare.telemetry.grpc.PatientRef;
import tn.smarthealthcare.telemetry.grpc.StreamSummary;
import tn.smarthealthcare.telemetry.grpc.SubscribeRequest;
import tn.smarthealthcare.telemetry.grpc.TelemetryGrpc;
import tn.smarthealthcare.telemetry.grpc.VitalList;
import tn.smarthealthcare.telemetry.grpc.VitalSample;

/** Implémentation du contrat contracts/telemetry.proto. */
@Component
public class TelemetryGrpcService extends TelemetryGrpc.TelemetryImplBase {

    private static final Logger log = LoggerFactory.getLogger(TelemetryGrpcService.class);
    private static final int DEFAULT_VITALS = 20;
    private static final int DEFAULT_ALERTS = 50;
    private static final int MAX_LIMIT = 500;

    private final TelemetryService service;
    private final TelemetryRepository repo;
    private final AlertBus bus;

    public TelemetryGrpcService(TelemetryService service, TelemetryRepository repo, AlertBus bus) {
        this.service = service;
        this.repo = repo;
        this.bus = bus;
    }

    @Override
    public StreamObserver<VitalSample> streamVitals(StreamObserver<StreamSummary> response) {
        return new StreamObserver<>() {
            long accepted = 0;
            long rejected = 0;

            @Override
            public void onNext(VitalSample sample) {
                try {
                    service.process(sample);
                    accepted++;
                } catch (IllegalArgumentException e) {
                    rejected++;
                    service.countRejected();
                    log.warn("mesure rejetée : {}", e.getMessage());
                } catch (RuntimeException e) {
                    rejected++;
                    service.countRejected();
                    log.error("erreur de traitement d'une mesure", e);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("flux de mesures interrompu après {} mesures : {}", accepted, t.toString());
            }

            @Override
            public void onCompleted() {
                response.onNext(StreamSummary.newBuilder().setAccepted(accepted).setRejected(rejected).build());
                response.onCompleted();
            }
        };
    }

    @Override
    public void subscribeAlerts(SubscribeRequest request, StreamObserver<Alert> response) {
        ServerCallStreamObserver<Alert> out = (ServerCallStreamObserver<Alert>) response;
        Runnable unsubscribe = bus.subscribe(request, out);
        out.setOnCancelHandler(unsubscribe);
        out.setOnCloseHandler(unsubscribe);
    }

    @Override
    public void getLatestVitals(PatientRef request, StreamObserver<VitalSample> response) {
        if (request.getPatientId().isBlank()) {
            response.onError(Status.INVALID_ARGUMENT.withDescription("patient_id obligatoire").asRuntimeException());
            return;
        }
        repo.latest(request.getPatientId()).ifPresentOrElse(
                v -> { response.onNext(v); response.onCompleted(); },
                () -> response.onError(Status.NOT_FOUND
                        .withDescription("aucune mesure pour " + request.getPatientId()).asRuntimeException()));
    }

    @Override
    public void listVitals(ListVitalsRequest request, StreamObserver<VitalList> response) {
        if (request.getPatientId().isBlank()) {
            response.onError(Status.INVALID_ARGUMENT.withDescription("patient_id obligatoire").asRuntimeException());
            return;
        }
        int limit = limit(request.getLimit(), DEFAULT_VITALS);
        response.onNext(VitalList.newBuilder().addAllSamples(repo.list(request.getPatientId(), limit)).build());
        response.onCompleted();
    }

    @Override
    public void listAlerts(ListAlertsRequest request, StreamObserver<AlertList> response) {
        int limit = limit(request.getLimit(), DEFAULT_ALERTS);
        response.onNext(AlertList.newBuilder()
                .addAllAlerts(repo.list(request.getPatientId(), request.getStatus(), limit)).build());
        response.onCompleted();
    }

    private static int limit(int requested, int byDefault) {
        return requested <= 0 ? byDefault : Math.min(requested, MAX_LIMIT);
    }
}
