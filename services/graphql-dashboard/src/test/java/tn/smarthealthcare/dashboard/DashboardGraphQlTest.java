package tn.smarthealthcare.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Le schéma de contracts/dashboard.graphql est servi tel quel ; les résolveurs sont testés avec des clients simulés. */
@GraphQlTest(DashboardController.class)
@Import(DashboardErrorResolver.class)
class DashboardGraphQlTest {

    @Autowired GraphQlTester graphQl;
    @MockBean PatientsClient patients;
    @MockBean TelemetryClient telemetry;
    @MockBean AlertStream alertStream;

    private static final Map<String, Object> SARRA = Map.of("id", "PAT-000004", "nom", "Bouzid", "prenom", "Sarra",
            "sexe", "F", "dateNaissance", "1946-06-28", "service", "cardiologie", "niveauRisque", "ELEVE");

    private static final Map<String, Object> VITAL = Map.of("measuredAt", "2026-11-24T10:00:00Z", "heartRate", 148,
            "respRate", 27, "spo2", 90.0, "consciousness", "VOICE");

    private static final Map<String, Object> ALERTE = Map.of("id", "a1", "patientId", "PAT-000004",
            "raisedAt", "2026-11-24T10:00:00Z", "severity", "CRITIQUE", "news2Score", 9, "trendWorsening", false,
            "status", "OPEN", "reason", "NEWS2 = 9");

    @Test
    void laFichePatientTientEnUneSeuleRequete() {
        when(patients.get(eq("PAT-000004"), anyString())).thenReturn(Mono.just(SARRA));
        when(telemetry.latestVital(eq("PAT-000004"), anyString())).thenReturn(Mono.just(VITAL));
        when(telemetry.alerts(eq("PAT-000004"), eq("OPEN"), anyInt(), anyString())).thenReturn(Mono.just(List.of(ALERTE)));
        when(patients.prescriptions(eq("PAT-000004"), anyString())).thenReturn(Mono.just(List.of(Map.of("id", "ORD-1",
                "medicaments", List.of(Map.of("nom", "Amiodarone", "posologie", "1 cp par jour"))))));

        graphQl.document("""
                { patient(id: "PAT-000004") {
                    nom niveauRisque
                    latestVital { heartRate consciousness }
                    alerts(status: OPEN) { severity news2Score }
                    treatment { ordonnances { id medicaments { nom } } }
                } }""")
                .execute()
                .errors().verify()
                .path("patient.nom").entity(String.class).isEqualTo("Bouzid")
                .path("patient.niveauRisque").entity(String.class).isEqualTo("ELEVE")
                .path("patient.latestVital.heartRate").entity(Integer.class).isEqualTo(148)
                .path("patient.alerts[0].severity").entity(String.class).isEqualTo("CRITIQUE")
                .path("patient.treatment.ordonnances[0].medicaments[0].nom").entity(String.class).isEqualTo("Amiodarone");
    }

    @Test
    void laListeDesPatientsTransmetLesFiltres() {
        when(patients.list(any(), anyString())).thenReturn(Mono.just(List.of(SARRA)));
        graphQl.document("""
                { patients(filtre: {service: "cardiologie", niveauRisque: ELEVE}) { id } }""")
                .execute().errors().verify()
                .path("patients[0].id").entity(String.class).isEqualTo("PAT-000004");
        verify(patients).list(eq(Map.of("service", "cardiologie", "niveauRisque", "ELEVE")), anyString());
    }

    @Test
    void sansLeRestLesConstantesRestentEtSeuleLOrdonnanceEchoue() {
        when(patients.get(eq("PAT-000004"), anyString())).thenReturn(Mono.just(SARRA));
        when(telemetry.latestVital(eq("PAT-000004"), anyString())).thenReturn(Mono.just(VITAL));
        when(patients.prescriptions(eq("PAT-000004"), anyString())).thenReturn(Mono.error(
                new WebClientRequestException(new java.net.ConnectException("refus"), org.springframework.http.HttpMethod.GET,
                        java.net.URI.create("http://rest-portal:8082/x"), org.springframework.http.HttpHeaders.EMPTY)));

        graphQl.document("""
                { patient(id: "PAT-000004") { nom latestVital { heartRate } treatment { ordonnances { id } } } }""")
                .execute()
                .errors().expect(e -> "PORTAIL_INDISPONIBLE".equals(e.getExtensions().get("code"))
                        && e.getPath().contains("treatment"))
                .verify()
                .path("patient.nom").entity(String.class).isEqualTo("Bouzid")
                .path("patient.latestVital.heartRate").entity(Integer.class).isEqualTo(148)
                .path("patient.treatment").valueIsNull();
    }

    @Test
    void sansLaTelemetrieLaFicheResteAffichableAvecUneErreurCiblee() {
        when(patients.get(eq("PAT-000004"), anyString())).thenReturn(Mono.just(SARRA));
        when(telemetry.latestVital(eq("PAT-000004"), anyString())).thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));
        when(telemetry.alerts(eq("PAT-000004"), any(), anyInt(), anyString())).thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));
        when(patients.prescriptions(eq("PAT-000004"), anyString())).thenReturn(Mono.just(List.of()));

        graphQl.document("""
                { patient(id: "PAT-000004") { nom latestVital { heartRate } alerts { id } treatment { ordonnances { id } } } }""")
                .execute()
                .errors().expect(e -> "TELEMETRIE_INDISPONIBLE".equals(e.getExtensions().get("code"))).verify()
                .path("patient.nom").entity(String.class).isEqualTo("Bouzid")
                .path("patient.alerts").entityList(Object.class).hasSize(0)
                .path("patient.latestVital").valueIsNull()
                .path("patient.treatment.ordonnances").entityList(Object.class).hasSize(0);
    }

    @Test
    void unPatientInconnuDonneNull() {
        when(patients.get(eq("PAT-999999"), anyString())).thenReturn(Mono.empty());
        graphQl.document("{ patient(id: \"PAT-999999\") { nom } }").execute().errors().verify()
                .path("patient").valueIsNull();
    }

    @Test
    void laSubscriptionFiltreParGraviteMinimale() {
        when(alertStream.alerts("CRITIQUE")).thenReturn(Flux.just(ALERTE));
        Flux<String> flux = graphQl.document("subscription { alertCreated(minSeverity: CRITIQUE) { id severity } }")
                .executeSubscription().toFlux("alertCreated.severity", String.class);
        StepVerifier.create(flux).expectNext("CRITIQUE").verifyComplete();
    }

    @Test
    void laGraviteMinimaleParDefautEstElevee() {
        when(alertStream.alerts("ELEVEE")).thenReturn(Flux.empty());
        StepVerifier.create(graphQl.document("subscription { alertCreated { id } }")
                .executeSubscription().toFlux("alertCreated.id", String.class)).verifyComplete();
        verify(alertStream).alerts("ELEVEE");
    }
}
