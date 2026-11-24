package tn.smarthealthcare.dashboard;

import graphql.execution.DataFetcherResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Résolveurs du schéma contracts/dashboard.graphql :
 * profil et traitement via le REST du portail, constantes et alertes via le gRPC de télémétrie.
 */
@Controller
public class DashboardController {

    private final PatientsClient patients;
    private final TelemetryClient telemetry;
    private final AlertStream alertStream;

    public DashboardController(PatientsClient patients, TelemetryClient telemetry, AlertStream alertStream) {
        this.patients = patients;
        this.telemetry = telemetry;
        this.alertStream = alertStream;
    }

    private static String cid(String fromContext) {
        return fromContext != null ? fromContext : UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ Query

    @QueryMapping
    public Mono<List<Map<String, Object>>> patients(@Argument Map<String, Object> filtre,
                                                    @ContextValue(required = false) String correlationId) {
        return patients.list(filtre, cid(correlationId));
    }

    @QueryMapping
    public Mono<Map<String, Object>> patient(@Argument String id, @ContextValue(required = false) String correlationId) {
        return patients.get(id, cid(correlationId));
    }

    @QueryMapping
    public Mono<DataFetcherResult<List<Map<String, Object>>>> alerts(@Argument String patientId, @Argument String status,
                                                                      @Argument int limit,
                                                                      @ContextValue(required = false) String correlationId) {
        return degrade(telemetry.alerts(patientId, status, limit, cid(correlationId)));
    }

    // ------------------------------------------------------------------ Patient

    @SchemaMapping(typeName = "Patient", field = "latestVital")
    public Mono<Map<String, Object>> latestVital(Map<String, Object> patient,
                                                 @ContextValue(required = false) String correlationId) {
        return telemetry.latestVital((String) patient.get("id"), cid(correlationId));
    }

    @SchemaMapping(typeName = "Patient", field = "vitals")
    public Mono<DataFetcherResult<List<Map<String, Object>>>> vitals(Map<String, Object> patient, @Argument int limit,
                                                                     @ContextValue(required = false) String correlationId) {
        return degrade(telemetry.vitals((String) patient.get("id"), limit, cid(correlationId)));
    }

    @SchemaMapping(typeName = "Patient", field = "alerts")
    public Mono<DataFetcherResult<List<Map<String, Object>>>> patientAlerts(Map<String, Object> patient,
                                                                             @Argument String status,
                                                                             @ContextValue(required = false) String correlationId) {
        return degrade(telemetry.alerts((String) patient.get("id"), status, 50, cid(correlationId)));
    }

    @SchemaMapping(typeName = "Patient", field = "treatment")
    public Mono<Map<String, Object>> treatment(Map<String, Object> patient,
                                               @ContextValue(required = false) String correlationId) {
        return patients.prescriptions((String) patient.get("id"), cid(correlationId))
                .map(ordonnances -> Map.<String, Object>of("ordonnances", ordonnances));
    }

    // ------------------------------------------------------------------ Alert

    @SchemaMapping(typeName = "Alert", field = "patient")
    public Mono<Map<String, Object>> alertPatient(Map<String, Object> alert,
                                                  @ContextValue(required = false) String correlationId) {
        return patients.get((String) alert.get("patientId"), cid(correlationId));
    }

    // ------------------------------------------------------------------ Subscription

    @SubscriptionMapping
    public Flux<Map<String, Object>> alertCreated(@Argument String minSeverity) {
        return alertStream.alerts(minSeverity);
    }

    // ------------------------------------------------------------------ erreurs partielles

    /**
     * Les listes du schéma ne peuvent pas être nulles : si la télémétrie est en panne, on renvoie une liste vide
     * ET une erreur ciblée, au lieu de faire échouer toute la fiche du patient.
     */
    private static Mono<DataFetcherResult<List<Map<String, Object>>>> degrade(Mono<List<Map<String, Object>>> source) {
        return source
                .map(liste -> DataFetcherResult.<List<Map<String, Object>>>newResult().data(liste).build())
                .onErrorResume(e -> Mono.just(DataFetcherResult.<List<Map<String, Object>>>newResult()
                        .data(List.of())
                        .error(DashboardErrorResolver.erreur(
                                "Service de télémétrie indisponible : liste vide renvoyée", "TELEMETRIE_INDISPONIBLE"))
                        .build()));
    }
}
