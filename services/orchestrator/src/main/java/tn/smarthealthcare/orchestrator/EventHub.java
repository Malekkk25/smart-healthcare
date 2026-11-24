package tn.smarthealthcare.orchestrator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Diffuse en SSE chaque changement d'étape (page admin des interventions). */
@Component
public class EventHub {

    private static final Logger log = LoggerFactory.getLogger(EventHub.class);

    private final List<SseEmitter> all = new CopyOnWriteArrayList<>();
    private final Map<String, List<SseEmitter>> byWorkflow = new ConcurrentHashMap<>();

    public SseEmitter subscribeAll() {
        return register(all);
    }

    public SseEmitter subscribe(String workflowId) {
        return register(byWorkflow.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()));
    }

    private SseEmitter register(List<SseEmitter> target) {
        SseEmitter emitter = new SseEmitter(0L); // sans délai d'expiration
        target.add(emitter);
        Runnable remove = () -> target.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().comment("connecté"));
        } catch (IOException | IllegalStateException e) {
            remove.run();
        }
        return emitter;
    }

    /** event : WORKFLOW_STARTED, STEP_UPDATED, ADMIN_ALERT, WORKFLOW_ENDED. */
    public void publish(String workflowId, String event, Object data) {
        send(all, event, data);
        send(byWorkflow.getOrDefault(workflowId, List.of()), event, data);
    }

    private void send(List<SseEmitter> targets, String event, Object data) {
        for (SseEmitter e : targets) {
            try {
                synchronized (e) {
                    e.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException ex) {
                targets.remove(e);
            }
        }
    }

    /** Commentaire SSE toutes les 15 s pour garder les connexions ouvertes. */
    @Scheduled(fixedRate = 15_000)
    void keepalive() {
        for (List<SseEmitter> targets : concat()) {
            for (SseEmitter e : targets) {
                try {
                    synchronized (e) {
                        e.send(SseEmitter.event().comment("keepalive"));
                    }
                } catch (IOException | IllegalStateException ex) {
                    targets.remove(e);
                }
            }
        }
    }

    private List<List<SseEmitter>> concat() {
        List<List<SseEmitter>> l = new java.util.ArrayList<>(byWorkflow.values());
        l.add(all);
        return l;
    }

    int subscribers() {
        return all.size() + byWorkflow.values().stream().mapToInt(List::size).sum();
    }

    void logSubscribers() {
        log.debug("{} abonné(s) SSE", subscribers());
    }
}
