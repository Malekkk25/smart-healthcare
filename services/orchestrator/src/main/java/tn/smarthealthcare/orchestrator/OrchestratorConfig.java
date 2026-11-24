package tn.smarthealthcare.orchestrator;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tn.smarthealthcare.orchestrator.Ports.DossierPort;
import tn.smarthealthcare.orchestrator.Ports.EhrPort;
import tn.smarthealthcare.orchestrator.Ports.PortalPort;
import tn.smarthealthcare.orchestrator.Ports.TriagePort;

@Configuration
public class OrchestratorConfig {

    /** Les interventions s'exécutent en parallèle, chacune dans son propre thread. */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor workflowExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(8);
        e.setMaxPoolSize(16);
        e.setQueueCapacity(200);
        e.setThreadNamePrefix("workflow-");
        return e;
    }

    @Bean
    public WorkflowEngine workflowEngine(WorkflowRepository repo, EventHub hub, EhrPort ehr, PortalPort portal,
                                         DossierPort dossier, TriagePort triage, OrchestratorProperties p,
                                         Executor workflowExecutor, MeterRegistry metrics) {
        var settings = new WorkflowEngine.Settings(p.defaultActe(), p.attempts(), p.waitMs(),
                p.deferredDelaySeconds(), p.deferredMaxAttempts());
        return new WorkflowEngine(repo, hub, ehr, portal, dossier, triage, settings, workflowExecutor, metrics);
    }
}
