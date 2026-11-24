package tn.smarthealthcare.telemetry;

import io.grpc.stub.ServerCallStreamObserver;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tn.smarthealthcare.telemetry.grpc.Alert;
import tn.smarthealthcare.telemetry.grpc.SubscribeRequest;

/** Diffuse chaque nouvelle alerte aux abonnés de SubscribeAlerts (filtre par patient et par gravité minimale). */
@Component
public class AlertBus {

    private static final Logger log = LoggerFactory.getLogger(AlertBus.class);

    private record Subscriber(Set<String> patients, int minRank, ServerCallStreamObserver<Alert> out) {
        boolean accepts(Alert a) {
            return (patients.isEmpty() || patients.contains(a.getPatientId()))
                    && a.getSeverity().getNumber() >= minRank;
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** @return l'action à exécuter à la déconnexion de l'abonné */
    public Runnable subscribe(SubscribeRequest request, ServerCallStreamObserver<Alert> out) {
        Subscriber s = new Subscriber(new HashSet<>(request.getPatientIdsList()),
                request.getMinSeverityValue(), out);
        subscribers.add(s);
        log.info("abonné SubscribeAlerts ajouté (patients={}, gravité min={}) : {} abonné(s)",
                s.patients().isEmpty() ? "tous" : s.patients(), request.getMinSeverity(), subscribers.size());
        return () -> {
            subscribers.remove(s);
            log.info("abonné SubscribeAlerts retiré : {} abonné(s)", subscribers.size());
        };
    }

    public void publish(Alert alert) {
        for (Subscriber s : subscribers) {
            if (!s.accepts(alert)) continue;
            try {
                synchronized (s.out()) { // un StreamObserver n'est pas thread-safe
                    if (s.out().isCancelled()) { subscribers.remove(s); continue; }
                    s.out().onNext(alert);
                }
            } catch (RuntimeException e) {
                log.warn("abonné injoignable, retiré : {}", e.toString());
                subscribers.remove(s);
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
