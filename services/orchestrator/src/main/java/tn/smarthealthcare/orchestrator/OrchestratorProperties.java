package tn.smarthealthcare.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Adresses des services appelés, délais et paramètres de reprise (voir application.yml). */
@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        String ehrUrl,
        String portalUrl,
        String dashboardUrl,
        String triageUrl,
        String telemetryTarget,
        String defaultActe,
        String triggerSeverity,
        int timeoutMs,
        int attempts,
        long waitMs,
        long deferredDelaySeconds,
        int deferredMaxAttempts,
        String ehrUser,
        String ehrPassword) {}
