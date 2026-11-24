package tn.smarthealthcare.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tn.smarthealthcare.orchestrator.Model.Step;
import tn.smarthealthcare.orchestrator.Model.Workflow;
import tn.smarthealthcare.orchestrator.Ports.DispatchOutcome;
import tn.smarthealthcare.orchestrator.Ports.DossierPort;
import tn.smarthealthcare.orchestrator.Ports.DroitsOutcome;
import tn.smarthealthcare.orchestrator.Ports.EhrPort;
import tn.smarthealthcare.orchestrator.Ports.NoResourceException;
import tn.smarthealthcare.orchestrator.Ports.PortalPort;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;
import tn.smarthealthcare.orchestrator.Ports.TriagePort;

/**
 * Le workflow d'urgence avec des services simulés (pannes injectées) et une base H2 :
 * nominal, idempotence, mode dégradé SOAP, replis REST et GraphQL, rejeu différé, disjoncteur, compensation.
 */
class WorkflowEngineTest {

    // ------------------------------------------------------------------ doublures des services appelés

    static class FakeEhr implements EhrPort {
        Supplier<DroitsOutcome> behaviour = () -> new DroitsOutcome(true, 80, "ok", null, null);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public DroitsOutcome verifierDroits(String patientId, String acte, String cid) {
            calls.incrementAndGet();
            return behaviour.get();
        }

        @Override
        public int compterActes(String patientId, String cid) {
            return 3;
        }
    }

    static class FakePortal implements PortalPort {
        Supplier<DispatchOutcome> dispatchBehaviour = () -> new DispatchOutcome("DSP-1", "AMB-03", 7, null);
        Supplier<String> bookingBehaviour = () -> "APT-9";
        final AtomicInteger dispatchCalls = new AtomicInteger();
        final List<String> cancelled = new ArrayList<>();

        @Override
        public DispatchOutcome dispatch(String alertId, String patientId, String severity, double lat, double lon, String cid) {
            dispatchCalls.incrementAndGet();
            return dispatchBehaviour.get();
        }

        @Override
        public String bookEmergencyAppointment(String patientId, String alertId, String cid) {
            return bookingBehaviour.get();
        }

        @Override
        public void cancelAppointment(String appointmentId, String cid) {
            cancelled.add(appointmentId);
        }
    }

    static class FakeDossier implements DossierPort {
        Supplier<Map<String, Object>> behaviour = () -> Map.of("id", "PAT-000004", "nom", "Bouzid", "prenom", "Sarra",
                "service", "cardiologie", "pathologie", "Arythmie");

        @Override
        public Map<String, Object> fetch(String patientId, String cid) {
            return behaviour.get();
        }
    }

    static class FakeTriage implements TriagePort {
        Supplier<Map<String, Object>> behaviour = () -> Map.of("riskScore", 92, "riskLevel", "CRITIQUE",
                "summary", "Résumé IA", "source", "regles");

        @Override
        public Map<String, Object> triage(Map<String, Object> payload, String cid) {
            return behaviour.get();
        }
    }

    private static ServiceUnavailableException panne() {
        return new ServiceUnavailableException("panne simulée", null);
    }

    // ------------------------------------------------------------------ mise en place

    private WorkflowRepository repo;
    private FakeEhr ehr;
    private FakePortal portal;
    private FakeDossier dossier;
    private FakeTriage triage;
    private WorkflowEngine engine;

    @BeforeEach
    void init() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:wf" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new JdbcTemplate(ds).execute("CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        repo = new WorkflowRepository(new JdbcTemplate(ds), new ObjectMapper());
        ehr = new FakeEhr();
        portal = new FakePortal();
        dossier = new FakeDossier();
        triage = new FakeTriage();
        engine = new WorkflowEngine(repo, new EventHub(), ehr, portal, dossier, triage,
                new WorkflowEngine.Settings("C", 3, 1, 0, 5), Runnable::run, new SimpleMeterRegistry());
    }

    private Workflow demarrer(String alertId) {
        return engine.start(alertId, "PAT-000004", "CRITIQUE", 9, "NEWS2 = 9").workflow();
    }

    private Workflow recharger(Workflow w) {
        return repo.get(w.id()).orElseThrow();
    }

    private static Step etape(Workflow w, String name) {
        return w.steps().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ scénarios

    @Test
    void deroulementNominalEnQuatreEtapes() {
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("COMPLETED", w.status());
        assertEquals(List.of("ALERTE", "DROITS", "PRISE_EN_CHARGE", "FICHE"), w.steps().stream().map(Step::name).toList());
        w.steps().forEach(s -> assertEquals("SUCCEEDED", s.status(), s.name()));
        assertTrue(etape(w, "DROITS").detail().contains("droits ouverts, remboursement 80 %"));
        assertEquals("AMB-03, ETA 7 min", etape(w, "PRISE_EN_CHARGE").detail());
        assertEquals("AMB-03", w.ambulanceId());
        assertEquals(7, w.etaMinutes());
        assertNotNull(w.endedAt());
        assertNotNull(w.correlationId());
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) w.dossier().get("triage");
        assertEquals("Résumé IA", t.get("summary"));
    }

    @Test
    void uneAlerteDejaOuverteNeRelancePasLeWorkflow() {
        Workflow premier = demarrer("alert-1");
        var second = engine.start("alert-1", "PAT-000004", "CRITIQUE", 9, "NEWS2 = 9");
        assertFalse(second.created());
        assertEquals(premier.id(), second.workflow().id());
        assertEquals(1, portal.dispatchCalls.get(), "un seul envoi d'ambulance");
        assertEquals(1, repo.list(null, null, 50).size());
    }

    @Test
    void soapEnPanneActiveLeModeDegradeEtLesSecoursPartent() {
        ehr.behaviour = () -> { throw panne(); };
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("DEGRADED", w.status());
        assertEquals("DEGRADED", etape(w, "DROITS").status());
        assertEquals(3, etape(w, "DROITS").attempts());
        assertEquals("SOAP indisponible : vérification différée", etape(w, "DROITS").detail());
        assertEquals("SUCCEEDED", etape(w, "PRISE_EN_CHARGE").status(), "l'assurance ne bloque jamais les secours");
        assertEquals("SUCCEEDED", etape(w, "FICHE").status());
        assertEquals(1, repo.pendingCount(w.id()));
    }

    @Test
    void lesDroitsSontVerifiesApresCoupQuandLeSoapRevient() {
        ehr.behaviour = () -> { throw panne(); };
        Workflow w = demarrer("alert-1");
        ehr.behaviour = () -> new DroitsOutcome(true, 50, "ok", null, null);
        engine.processDue();
        w = recharger(w);
        assertEquals("SUCCEEDED", etape(w, "DROITS").status());
        assertTrue(etape(w, "DROITS").detail().startsWith("vérifié après coup"));
        assertEquals("COMPLETED", w.status());
        assertEquals(0, repo.pendingCount(w.id()));
    }

    @Test
    void unFaultSoapDeDroitsExpiresEstUnResultatPasUnePanne() {
        ehr.behaviour = () -> new DroitsOutcome(false, null, null, "DroitsExpiresFault", "Les droits CNAM du patient ont expiré");
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("SUCCEEDED", etape(w, "DROITS").status());
        assertEquals("droits CNAM expirés : facturation à régulariser", etape(w, "DROITS").detail());
        assertEquals(1, ehr.calls.get(), "pas de nouvel essai pour un résultat métier");
        assertEquals("AMB-03", w.ambulanceId(), "les secours partent malgré les droits expirés");
        assertEquals("COMPLETED", w.status());
        assertEquals(0, repo.pendingCount(w.id()));
    }

    @Test
    void unePanneRestPassagereEstAbsorbeeParLesEssais() {
        AtomicInteger n = new AtomicInteger();
        portal.dispatchBehaviour = () -> {
            if (n.incrementAndGet() < 3) throw panne();
            return new DispatchOutcome("DSP-1", "AMB-01", 4, null);
        };
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("SUCCEEDED", etape(w, "PRISE_EN_CHARGE").status());
        assertEquals(3, etape(w, "PRISE_EN_CHARGE").attempts());
        assertEquals(3, portal.dispatchCalls.get());
        assertNull(w.adminAlert());
    }

    @Test
    void apresTroisEssaisRestBasculeSurUnCreneauDUrgenceEtAlerteLAdmin() {
        portal.dispatchBehaviour = () -> { throw panne(); };
        Workflow w = recharger(demarrer("alert-1"));
        Step s = etape(w, "PRISE_EN_CHARGE");
        assertEquals("DEGRADED", s.status());
        assertEquals(3, s.attempts());
        assertTrue(s.detail().contains("créneau d'urgence APT-9"), s.detail());
        assertEquals("APT-9", w.appointmentId());
        assertNotNull(w.adminAlert());
        assertTrue(w.adminAlert().contains("APT-9"));
        assertEquals("DEGRADED", w.status());
    }

    @Test
    void sansRestNiCreneauLIntervention_EchoueAvecAlerteAdmin() {
        portal.dispatchBehaviour = () -> { throw panne(); };
        portal.bookingBehaviour = () -> { throw panne(); };
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("FAILED", etape(w, "PRISE_EN_CHARGE").status());
        assertEquals("FAILED", w.status());
        assertTrue(w.adminAlert().contains("intervention manuelle"));
        assertEquals("SUCCEEDED", etape(w, "FICHE").status(), "la fiche est quand même produite");
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) w.dossier().get("triage");
        assertNotNull(t);
    }

    @Test
    void sansAmbulanceLeRestPropose_UnCreneauEtLAdminEstPrevenu() {
        portal.dispatchBehaviour = () -> new DispatchOutcome("DSP-2", null, null, "APT-77");
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("DEGRADED", etape(w, "PRISE_EN_CHARGE").status());
        assertEquals("APT-77", w.appointmentId());
        assertTrue(w.adminAlert().contains("APT-77"));
    }

    @Test
    void plusAucuneRessourceEstUnEchecSansNouvelEssai() {
        portal.dispatchBehaviour = () -> { throw new NoResourceException("aucune ressource"); };
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("FAILED", etape(w, "PRISE_EN_CHARGE").status());
        assertEquals(1, portal.dispatchCalls.get(), "inutile de réessayer");
        assertNotNull(w.adminAlert());
    }

    @Test
    void graphqlEnPanneMetLaFicheEnFileEtLaRejoue() {
        dossier.behaviour = () -> { throw panne(); };
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("DEGRADED", etape(w, "FICHE").status());
        assertEquals("GraphQL indisponible : fiche mise en file", etape(w, "FICHE").detail());
        assertNull(w.dossier());
        assertEquals("DEGRADED", w.status());

        dossier.behaviour = () -> Map.of("id", "PAT-000004", "nom", "Bouzid", "prenom", "Sarra");
        engine.processDue();
        w = recharger(w);
        assertEquals("SUCCEEDED", etape(w, "FICHE").status());
        assertTrue(etape(w, "FICHE").detail().startsWith("fiche rejouée"));
        assertNotNull(w.dossier());
        assertEquals("COMPLETED", w.status());
    }

    @Test
    void sansServiceIaLaFicheUtiliseLeResumeLocal() {
        triage.behaviour = () -> { throw panne(); };
        Workflow w = recharger(demarrer("alert-1"));
        assertEquals("SUCCEEDED", etape(w, "FICHE").status());
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) w.dossier().get("triage");
        assertEquals("template-orchestrateur", t.get("source"));
        String resume = (String) t.get("summary");
        assertTrue(resume.contains("Sarra Bouzid") && resume.contains("AMB-03") && resume.contains("NEWS2 9"), resume);
    }

    @Test
    void leDisjoncteurSOuvreEtEvitedAppelerUnServiceMort() {
        ehr.behaviour = () -> { throw panne(); };
        demarrer("alert-1"); // 3 appels en échec
        demarrer("alert-2"); // 4e appel en échec : le disjoncteur s'ouvre, les suivants sont refusés sans appel
        assertEquals(4, ehr.calls.get());
        Workflow second = recharger(repo.findByAlertId("alert-2").orElseThrow());
        assertEquals("DEGRADED", etape(second, "DROITS").status());
        assertEquals("SUCCEEDED", etape(second, "PRISE_EN_CHARGE").status());
    }

    @Test
    void lAnnulationCompenseLeRendezVousDUrgence() {
        portal.dispatchBehaviour = () -> { throw panne(); };
        Workflow w = demarrer("alert-1");
        ehr.behaviour = () -> { throw panne(); };
        Workflow annule = engine.cancel(w.id());
        assertEquals(List.of("APT-9"), portal.cancelled);
        assertEquals("FAILED", annule.status());
        assertTrue(annule.adminAlert().contains("rendez-vous d'urgence APT-9 annulé"));
        assertEquals(0, repo.pendingCount(w.id()), "les tâches différées sont abandonnées");
    }

    @Test
    void lAnnulationIndiqueQueLAmbulanceNePeutPasEtreRappelee() {
        Workflow w = demarrer("alert-1");
        Workflow annule = engine.cancel(w.id());
        assertTrue(annule.adminAlert().contains("non rappelable"));
        assertTrue(portal.cancelled.isEmpty());
    }

    @Test
    void lesEvenementsSuiventLOrdreDesEtapes() {
        Workflow w = demarrer("alert-1");
        List<Map<String, Object>> journal = repo.journal(w.id());
        List<String> ordre = journal.stream().filter(l -> "SUCCEEDED".equals(l.get("status")))
                .map(l -> (String) l.get("step")).toList();
        assertEquals(List.of("ALERTE", "DROITS", "PRISE_EN_CHARGE", "FICHE"), ordre);
    }

    @Test
    void leStatutGlobalDecouleDesEtapes() {
        assertEquals("COMPLETED", Model.computeStatus(List.of(new Step("A", "SUCCEEDED", 1, null))));
        assertEquals("DEGRADED", Model.computeStatus(List.of(new Step("A", "SUCCEEDED", 1, null), new Step("B", "DEGRADED", 3, null))));
        assertEquals("FAILED", Model.computeStatus(List.of(new Step("A", "DEGRADED", 1, null), new Step("B", "FAILED", 3, null))));
        assertEquals("RUNNING", Model.computeStatus(List.of(new Step("A", "SUCCEEDED", 1, null), new Step("B", "PENDING", 0, null))));
    }

    @Test
    void leDomicileSimuleEstStableEtDansLeGrandTunis() {
        double[] a = HomeLocation.of("PAT-000004");
        assertEquals(a[0], HomeLocation.of("PAT-000004")[0]);
        assertTrue(a[0] > 36.7 && a[0] < 36.9 && a[1] > 10.1 && a[1] < 10.3);
        assertTrue(a[0] != HomeLocation.of("PAT-000005")[0] || a[1] != HomeLocation.of("PAT-000005")[1]);
    }
}
