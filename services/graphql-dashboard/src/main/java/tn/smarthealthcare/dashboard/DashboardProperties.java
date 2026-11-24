package tn.smarthealthcare.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Adresses des services appelés (noms Docker par défaut) et délai maximal d'un appel. */
@ConfigurationProperties(prefix = "dashboard")
public record DashboardProperties(String restUrl, String telemetryTarget, int timeoutMs) {}
