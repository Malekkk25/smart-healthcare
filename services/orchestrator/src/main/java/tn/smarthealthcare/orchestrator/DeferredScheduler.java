package tn.smarthealthcare.orchestrator;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rejoue périodiquement les tâches différées (droits vérifiés après coup, fiches mises en file). */
@Component
public class DeferredScheduler {

    private final WorkflowEngine engine;

    public DeferredScheduler(WorkflowEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void tick() {
        engine.processDue();
    }
}
