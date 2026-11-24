package tn.smarthealthcare.orchestrator.clients;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tn.smarthealthcare.orchestrator.OrchestratorProperties;
import tn.smarthealthcare.orchestrator.Ports.DispatchOutcome;
import tn.smarthealthcare.orchestrator.Ports.NoResourceException;
import tn.smarthealthcare.orchestrator.Ports.PortalPort;
import tn.smarthealthcare.orchestrator.Ports.RefusedException;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;

/** Client du service REST du portail (contracts/portal.openapi.yaml). */
@Component
public class RestPortalClient implements PortalPort {

    private final RestClient rest;
    private final String baseUrl;

    public RestPortalClient(OrchestratorProperties props) {
        this.rest = Http.client(props.timeoutMs());
        this.baseUrl = props.portalUrl();
    }

    @Override
    public DispatchOutcome dispatch(String alertId, String patientId, String severity, double latitude, double longitude,
                                    String correlationId) {
        Map<String, Object> body = Map.of("patientId", patientId, "alertId", alertId, "severity", severity,
                "location", Map.of("latitude", latitude, "longitude", longitude));
        Map<String, Object> r = exec(() -> rest.post().uri(baseUrl + "/emergency/dispatch")
                .header("X-Correlation-Id", correlationId).body(body).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
        return new DispatchOutcome((String) r.get("dispatchId"), (String) r.get("ambulanceId"),
                r.get("etaMinutes") == null ? null : ((Number) r.get("etaMinutes")).intValue(),
                (String) r.get("fallbackAppointmentId"));
    }

    @Override
    public String bookEmergencyAppointment(String patientId, String alertId, String correlationId) {
        List<Map<String, Object>> slots = exec(() -> rest.get().uri(baseUrl + "/slots")
                .header("X-Correlation-Id", correlationId).retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        List<Map<String, Object>> urgences = slots.stream().filter(s -> Boolean.TRUE.equals(s.get("urgence"))).limit(3).toList();
        if (urgences.isEmpty()) throw new NoResourceException("aucun créneau d'urgence libre");
        for (Map<String, Object> slot : urgences) {
            try {
                Map<String, Object> rdv = exec(() -> rest.post().uri(baseUrl + "/appointments")
                        .header("X-Correlation-Id", correlationId)
                        .body(Map.of("patientId", patientId, "slotId", slot.get("id"), "motif", "Urgence (alerte " + alertId + ")"))
                        .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {}));
                return (String) rdv.get("id");
            } catch (NoResourceException conflit) {
                // créneau pris entre-temps : on essaie le suivant
            }
        }
        throw new NoResourceException("aucun créneau d'urgence réservable");
    }

    @Override
    public void cancelAppointment(String appointmentId, String correlationId) {
        exec(() -> {
            rest.delete().uri(baseUrl + "/appointments/{id}", appointmentId)
                    .header("X-Correlation-Id", correlationId).retrieve().toBodilessEntity();
            return Boolean.TRUE;
        });
    }

    /** Pannes (réseau, délai, 5xx) -> ServiceUnavailableException ; 409 -> NoResourceException ; autres 4xx -> RefusedException. */
    private static <T> T exec(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) throw new NoResourceException(e.getResponseBodyAsString());
            throw new RefusedException("HTTP " + e.getStatusCode().value() + " : " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new ServiceUnavailableException("REST injoignable : " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("REST : " + e, e);
        }
    }
}
