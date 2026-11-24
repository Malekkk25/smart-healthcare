package tn.smarthealthcare.orchestrator.clients;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tn.smarthealthcare.orchestrator.OrchestratorProperties;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;
import tn.smarthealthcare.orchestrator.Ports.TriagePort;

/** Client du service IA de triage (score de risque et résumé de la fiche). */
@Component
public class AiTriageClient implements TriagePort {

    private final RestClient rest;
    private final String url;

    public AiTriageClient(OrchestratorProperties props) {
        // Le résumé par un modèle peut être plus lent : délai plus large, puis repli sur le texte local
        this.rest = Http.client(props.timeoutMs() * 3);
        this.url = props.triageUrl();
    }

    @Override
    public Map<String, Object> triage(Map<String, Object> payload, String correlationId) {
        try {
            return rest.post().uri(url + "/triage").header("X-Correlation-Id", correlationId).body(payload)
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("service IA indisponible : " + e.getMessage(), e);
        }
    }
}
