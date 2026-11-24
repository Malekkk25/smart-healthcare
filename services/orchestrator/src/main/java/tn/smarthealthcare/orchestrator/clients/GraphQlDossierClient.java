package tn.smarthealthcare.orchestrator.clients;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tn.smarthealthcare.orchestrator.OrchestratorProperties;
import tn.smarthealthcare.orchestrator.Ports.DossierPort;
import tn.smarthealthcare.orchestrator.Ports.RefusedException;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;

/** Client du service GraphQL : une seule requête ramène profil, constantes, alertes et traitement. */
@Component
public class GraphQlDossierClient implements DossierPort {

    static final String QUERY = """
            query Fiche($id: ID!) { patient(id: $id) {
              id nom prenom sexe dateNaissance service pathologie niveauRisque
              latestVital { measuredAt heartRate respRate spo2 systolicBp diastolicBp temperature onOxygen consciousness }
              alerts(status: OPEN) { id raisedAt severity news2Score reason trendWorsening }
              treatment { ordonnances { id date doctorId medicaments { nom posologie dureeJours } } }
            } }""";

    private final RestClient rest;
    private final String url;

    public GraphQlDossierClient(OrchestratorProperties props) {
        this.rest = Http.client(props.timeoutMs() + 2000);
        this.url = props.dashboardUrl();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetch(String patientId, String correlationId) {
        Map<String, Object> response;
        try {
            response = rest.post().uri(url).header("X-Correlation-Id", correlationId)
                    .body(Map.of("query", QUERY, "variables", Map.of("id", patientId)))
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("GraphQL injoignable : " + e.getMessage(), e);
        }
        Map<String, Object> data = response == null ? null : (Map<String, Object>) response.get("data");
        Map<String, Object> patient = data == null ? null : (Map<String, Object>) data.get("patient");
        List<Object> errors = response == null ? null : (List<Object>) response.get("errors");
        if (patient == null) {
            // patient introuvable (pas d'erreur) ou service en échec (erreurs) ?
            if (errors == null || errors.isEmpty()) throw new RefusedException("patient introuvable : " + patientId);
            throw new ServiceUnavailableException("GraphQL en erreur : " + errors, null);
        }
        // Réponse partielle acceptée (par exemple traitement indisponible) : les erreurs sont conservées dans la fiche
        if (errors != null && !errors.isEmpty()) patient.put("erreursPartielles", errors);
        return patient;
    }
}
