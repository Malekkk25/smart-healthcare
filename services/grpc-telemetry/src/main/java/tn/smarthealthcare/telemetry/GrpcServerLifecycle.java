package tn.smarthealthcare.telemetry;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Démarre le serveur gRPC avec Spring ; ajoute la réflexion (grpcurl) et la santé gRPC. */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    static final Metadata.Key<String> CORRELATION = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private final TelemetryGrpcService service;
    private final int port;
    private Server server;

    public GrpcServerLifecycle(TelemetryGrpcService service, @Value("${grpc.port:9090}") int port) {
        this.service = service;
        this.port = port;
    }

    /** Reprend x-correlation-id des métadonnées (ou en génère un) et le place dans le MDC des logs. */
    static final class CorrelationInterceptor implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                             ServerCallHandler<Q, R> next) {
            String received = headers.get(CORRELATION);
            String id = received == null || received.isBlank() ? UUID.randomUUID().toString() : received;
            return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
                private void with(Runnable r) {
                    MDC.put("correlationId", id);
                    try { r.run(); } finally { MDC.remove("correlationId"); }
                }
                @Override public void onMessage(Q message) { with(() -> super.onMessage(message)); }
                @Override public void onHalfClose() { with(super::onHalfClose); }
                @Override public void onCancel() { with(super::onCancel); }
                @Override public void onComplete() { with(super::onComplete); }
            };
        }
    }

    @Override
    public void start() {
        try {
            HealthStatusManager health = new HealthStatusManager();
            server = ServerBuilder.forPort(port)
                    .addService(ServerInterceptors.intercept(service, new CorrelationInterceptor()))
                    .addService(ProtoReflectionService.newInstance())
                    .addService(health.getHealthService())
                    .build().start();
            log.info("serveur gRPC démarré sur le port {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("démarrage gRPC impossible sur le port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server == null) return;
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
