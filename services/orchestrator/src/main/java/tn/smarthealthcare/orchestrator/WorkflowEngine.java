package tn.smarthealthcare.orchestrator;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tn.smarthealthcare.orchestrator.Model.Step;
import tn.smarthealthcare.orchestrator.Model.Workflow;
import tn.smarthealthcare.orchestrator.Ports.DispatchOutcome;
import tn.smarthealthcare.orchestrator.Ports.DossierPort;
import tn.smarthealthcare.orchestrator.Ports.DroitsOutcome;
import tn.smarthealthcare.orchestrator.Ports.EhrPort;
import tn.smarthealthcare.orchestrator.Ports.NoResourceException;
import tn.smarthealthcare.orchestrator.Ports.PortalPort;
import tn.smarthealthcare.orchestrator.Ports.RefusedException;
import tn.smarthealthcare.orchestrator.Ports.ServiceUnavailableException;
import tn.smarthealthcare.orchestrator.Ports.TriagePort;

/**
 * Workflow d'urgence en 4 étapes, chacune avec son repli (schéma du plan de travail) :
 * <pre>
 * 1. ALERTE           alerte gRPC critique            repli : idempotence (alerte déjà ouverte = on ne relance pas)
 * 2. DROITS           SOAP verifierDroitsCNAM          repli : mode dégradé (on continue, droits vérifiés après coup)
 * 3. PRISE_EN_CHARGE  REST ambulance ou créneau urgent repli : 3 essais, puis créneau d'urgence et alerte admin
 * 4. FICHE            GraphQL + résumé IA              repli : fiche mise en file et rejouée
 * </pre>
 * Règle de fond : la couverture d'assurance ne bloque JAMAIS l'envoi des secours.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    public record Settings(String defaultActe, int attempts, long waitMs, long deferredDelaySeconds, int deferredMaxAttempts) {}

    public record Started(Workflow workflow, boolean created) {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static final String KIND_DROITS = "DROITS_DIFFEREE";
    public static final String KIND_FICHE = "FICHE_DIFFEREE";

    private final WorkflowRepository repo;
    private final EventHub hub;
    private final EhrPort ehr;
    private final PortalPort portal;
    private final DossierPort dossier;
    private final TriagePort triage;
    private final Settings settings;
    private final Executor executor;
    private final MeterRegistry metrics;

    private final CircuitBreaker soapBreaker = breaker("soap");
    private final CircuitBreaker restBreaker = breaker("rest");
    private final CircuitBreaker graphqlBreaker = breaker("graphql");

    public WorkflowEngine(WorkflowRepository repo, EventHub hub, EhrPort ehr, PortalPort portal, DossierPort dossier,
                          TriagePort triage, Settings settings, Executor executor, MeterRegistry metrics) {
        this.repo = repo;
        this.hub = hub;
        this.ehr = ehr;
        this.portal = portal;
        this.dossier = dossier;
        this.triage = triage;
        this.settings = settings;
        this.executor = executor;
        this.metrics = metrics;
    }

    private static CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(6).minimumNumberOfCalls(4).failureRateThreshold(60)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(1)
                .ignoreExceptions(RefusedException.class).build());
    }

    // =================================================================== point d'entrée (étape 1 : idempotence)

    /** Démarre l'intervention d'une alerte. Idempotent : une alerte déjà ouverte ne relance pas le workflow. */
    public Started start(String alertId, String patientId, String severity, int news2, String reason) {
        Optional<Workflow> created = repo.createIfAbsent(alertId, patientId, UUID.randomUUID().toString(), severity, news2, reason);
        if (created.isEmpty()) {
            metrics.counter("orchestrator_duplicate_alerts_total").increment();
            log.info("alerte {} déjà prise en charge : le workflow n'est pas relancé", alertId);
            return new Started(repo.findByAlertId(alertId).orElseThrow(), false);
        }
        Workflow w = created.get();
        hub.publish(w.id(), "WORKFLOW_STARTED", w);
        executor.execute(() -> run(w.id()));
        return new Started(w, true);
    }

    void run(String workflowId) {
        Workflow w = repo.get(workflowId).orElseThrow();
        MDC.put("correlationId", w.correlationId());
        MDC.put("alertId", w.alertId());
        try {
            safe(w, Model.ALERTE, this::stepAlerte);
            safe(w, Model.DROITS, this::stepDroits);
            safe(w, Model.PRISE_EN_CHARGE, this::stepPriseEnCharge);
            safe(w, Model.FICHE, this::stepFiche);
            finish(workflowId);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("alertId");
        }
    }

    private void safe(Workflow w, String step, Consumer<Workflow> action) {
        try {
            action.accept(w);
        } catch (RuntimeException e) {
            log.error("erreur inattendue à l'étape {}", step, e);
            setStep(w, step, "FAILED", 1, "erreur inattendue : " + e);
            raiseAdmin(w, "Erreur inattendue à l'étape " + step + " : " + e);
        }
    }

    // =================================================================== étape 1 : ALERTE

    private void stepAlerte(Workflow w) {
        setStep(w, Model.ALERTE, "SUCCEEDED", 1, "NEWS2 = " + w.news2Score() + (w.reason() == null ? "" : " : " + w.reason()));
    }

    // =================================================================== étape 2 : DROITS (SOAP, mode dégradé)

    private void stepDroits(Workflow w) {
        setStep(w, Model.DROITS, "RUNNING", 0, null);
        AtomicInteger attempts = new AtomicInteger();
        try {
            DroitsOutcome o = resilient(soapBreaker, attempts,
                    () -> ehr.verifierDroits(w.patientId(), settings.defaultActe(), w.correlationId()));
            setStep(w, Model.DROITS, "SUCCEEDED", attempts.get(), describeDroits(w, o));
        } catch (RuntimeException e) {
            // Mode dégradé : on continue, les droits seront vérifiés après coup
            repo.addPending(w.id(), KIND_DROITS, settings.deferredDelaySeconds());
            metrics.counter("orchestrator_fallback_total", "step", Model.DROITS).increment();
            setStep(w, Model.DROITS, "DEGRADED", attempts.get(), "SOAP indisponible : vérification différée");
            log.warn("SOAP indisponible ({}), vérification des droits différée", e.toString());
        }
    }

    private String describeDroits(Workflow w, DroitsOutcome o) {
        if (o.isFault()) {
            // Un Fault métier est un résultat, pas une panne : l'assurance ne bloque pas les secours
            return "DroitsExpiresFault".equals(o.fault())
                    ? "droits CNAM expirés : facturation à régulariser"
                    : "patient inconnu de l'assurance : facturation à régulariser";
        }
        if (!o.ouverts()) return "acte non pris en charge : " + o.motif();
        String detail = "droits ouverts, remboursement " + o.taux() + " %";
        try {
            detail += ", " + ehr.compterActes(w.patientId(), w.correlationId()) + " acte(s) certifié(s)";
        } catch (RuntimeException ignored) {
            // historique facultatif
        }
        return detail;
    }

    // =================================================================== étape 3 : PRISE EN CHARGE (REST)

    private void stepPriseEnCharge(Workflow w) {
        setStep(w, Model.PRISE_EN_CHARGE, "RUNNING", 0, null);
        AtomicInteger attempts = new AtomicInteger();
        double[] home = HomeLocation.of(w.patientId());
        String severity = w.severity() == null ? "CRITIQUE" : w.severity();
        try {
            DispatchOutcome d = resilient(restBreaker, attempts,
                    () -> portal.dispatch(w.alertId(), w.patientId(), severity, home[0], home[1], w.correlationId()));
            repo.setDispatch(w.id(), d.dispatchId(), d.ambulanceId(), d.etaMinutes(), d.fallbackAppointmentId());
            if (d.fallbackAppointmentId() != null) {
                setStep(w, Model.PRISE_EN_CHARGE, "DEGRADED", attempts.get(),
                        "aucune ambulance disponible : créneau d'urgence " + d.fallbackAppointmentId());
                raiseAdmin(w, "Aucune ambulance disponible : créneau d'urgence " + d.fallbackAppointmentId() + " réservé");
            } else {
                setStep(w, Model.PRISE_EN_CHARGE, "SUCCEEDED", attempts.get(),
                        d.ambulanceId() + ", ETA " + d.etaMinutes() + " min");
            }
        } catch (NoResourceException e) {
            setStep(w, Model.PRISE_EN_CHARGE, "FAILED", attempts.get(), "ni ambulance ni créneau d'urgence disponible");
            raiseAdmin(w, "Aucune ressource disponible pour " + w.patientId() + " : intervention manuelle requise");
        } catch (RefusedException e) {
            setStep(w, Model.PRISE_EN_CHARGE, "FAILED", attempts.get(), "demande refusée : " + e.getMessage());
            raiseAdmin(w, "Envoi refusé pour " + w.patientId() + " : " + e.getMessage());
        } catch (RuntimeException e) {
            repli(w, attempts.get(), e);
        }
    }

    /** Repli : après les essais, on réserve un créneau d'urgence et on alerte l'administrateur. */
    private void repli(Workflow w, int attempts, RuntimeException cause) {
        metrics.counter("orchestrator_fallback_total", "step", Model.PRISE_EN_CHARGE).increment();
        log.warn("envoi d'ambulance impossible après {} essai(s) ({}), repli sur un créneau d'urgence", attempts, cause.toString());
        try {
            String appointment = portal.bookEmergencyAppointment(w.patientId(), w.alertId(), w.correlationId());
            repo.setDispatch(w.id(), null, null, null, appointment);
            setStep(w, Model.PRISE_EN_CHARGE, "DEGRADED", attempts,
                    attempts + " essai(s) sans ambulance : créneau d'urgence " + appointment);
            raiseAdmin(w, "Ambulance non confirmée pour " + w.patientId() + " : créneau d'urgence " + appointment + " réservé");
        } catch (RuntimeException e2) {
            setStep(w, Model.PRISE_EN_CHARGE, "FAILED", attempts, "service REST indisponible : aucun envoi possible");
            raiseAdmin(w, "Service REST indisponible : AUCUN envoi pour " + w.patientId() + ", intervention manuelle immédiate");
        }
    }

    // =================================================================== étape 4 : FICHE (GraphQL + IA)

    private void stepFiche(Workflow w) {
        setStep(w, Model.FICHE, "RUNNING", 0, null);
        AtomicInteger attempts = new AtomicInteger();
        try {
            Map<String, Object> fiche = buildFiche(w, attempts);
            repo.setDossier(w.id(), fiche);
            setStep(w, Model.FICHE, "SUCCEEDED", attempts.get(), "fiche envoyée au médecin" + niveau(fiche));
        } catch (RuntimeException e) {
            repo.addPending(w.id(), KIND_FICHE, settings.deferredDelaySeconds());
            metrics.counter("orchestrator_fallback_total", "step", Model.FICHE).increment();
            setStep(w, Model.FICHE, "DEGRADED", attempts.get(), "GraphQL indisponible : fiche mise en file");
            log.warn("GraphQL indisponible ({}), fiche mise en file", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static String niveau(Map<String, Object> fiche) {
        Object t = fiche.get("triage");
        return t instanceof Map<?, ?> m && m.get("riskLevel") != null ? " (risque " + m.get("riskLevel") + ")" : "";
    }

    private Map<String, Object> buildFiche(Workflow w, AtomicInteger attempts) {
        Map<String, Object> patient = resilient(graphqlBreaker, attempts, () -> dossier.fetch(w.patientId(), w.correlationId()));
        Workflow fresh = repo.get(w.id()).orElse(w);

        Map<String, Object> alerte = new LinkedHashMap<>();
        alerte.put("alertId", fresh.alertId());
        alerte.put("severity", fresh.severity());
        alerte.put("news2Score", fresh.news2Score());
        alerte.put("reason", fresh.reason());

        Map<String, Object> intervention = new LinkedHashMap<>();
        intervention.put("dispatchId", fresh.dispatchId());
        intervention.put("ambulanceId", fresh.ambulanceId());
        intervention.put("etaMinutes", fresh.etaMinutes());
        intervention.put("appointmentId", fresh.appointmentId());
        intervention.put("adminAlert", fresh.adminAlert());

        Map<String, Object> fiche = new LinkedHashMap<>();
        fiche.put("alerte", alerte);
        fiche.put("intervention", intervention);
        fiche.put("patient", patient);
        fiche.put("triage", triageOuModele(w, fiche));
        return fiche;
    }

    /** Résumé IA ; repli obligatoire sur un texte généré localement si le service IA est indisponible. */
    private Map<String, Object> triageOuModele(Workflow w, Map<String, Object> fiche) {
        try {
            Map<String, Object> r = triage.triage(fiche, w.correlationId());
            if (r != null && r.get("summary") != null) return r;
        } catch (RuntimeException e) {
            log.warn("service IA indisponible ({}), résumé généré localement", e.toString());
            metrics.counter("orchestrator_fallback_total", "step", "TRIAGE").increment();
        }
        return localTriage(w, fiche);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> localTriage(Workflow w, Map<String, Object> fiche) {
        Map<String, Object> patient = (Map<String, Object>) fiche.getOrDefault("patient", Map.of());
        Map<String, Object> intervention = (Map<String, Object>) fiche.getOrDefault("intervention", Map.of());
        int news2 = w.news2Score() == null ? 0 : w.news2Score();
        String nom = patient.get("prenom") == null ? w.patientId() : patient.get("prenom") + " " + patient.get("nom");
        StringBuilder s = new StringBuilder(nom);
        if (patient.get("service") != null) s.append(" (").append(patient.get("service")).append(", ").append(patient.get("pathologie")).append(")");
        s.append(" : alerte ").append(w.severity()).append(", NEWS2 ").append(news2);
        if (w.reason() != null) s.append(" - ").append(w.reason());
        s.append(". ");
        if (intervention.get("ambulanceId") != null) {
            s.append("Ambulance ").append(intervention.get("ambulanceId")).append(", arrivée estimée dans ")
                    .append(intervention.get("etaMinutes")).append(" min.");
        } else if (intervention.get("appointmentId") != null) {
            s.append("Pas d'ambulance confirmée : créneau d'urgence ").append(intervention.get("appointmentId")).append(".");
        } else {
            s.append("Aucun envoi confirmé : intervention manuelle requise.");
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("riskScore", Math.min(100, news2 * 10 + ("CRITIQUE".equals(w.severity()) ? 10 : 0)));
        t.put("riskLevel", w.severity() == null ? "ELEVEE" : w.severity());
        t.put("summary", s.toString());
        t.put("source", "template-orchestrateur");
        return t;
    }

    // =================================================================== fin et statut

    private void finish(String workflowId) {
        Workflow w = repo.get(workflowId).orElseThrow();
        String status = Model.computeStatus(w.steps());
        repo.setStatus(workflowId, status, Instant.now());
        metrics.counter("orchestrator_workflows_total", "status", status).increment();
        hub.publish(workflowId, "WORKFLOW_ENDED", repo.get(workflowId).orElseThrow());
        log.info("intervention {} terminée : {}", workflowId, status);
    }

    /** Après un rejeu différé : recalcule le statut et le republie s'il a changé. */
    private void refreshStatus(String workflowId) {
        Workflow w = repo.get(workflowId).orElseThrow();
        String status = Model.computeStatus(w.steps());
        if (!status.equals(w.status())) {
            repo.setStatus(workflowId, status, w.endedAt() != null ? w.endedAt() : Instant.now());
            hub.publish(workflowId, "WORKFLOW_ENDED", repo.get(workflowId).orElseThrow());
        }
    }

    // =================================================================== tâches différées (rejeu)

    /** À appeler périodiquement : rejoue la vérification des droits et les fiches mises en file. */
    public void processDue() {
        for (WorkflowRepository.Pending p : repo.duePending(Instant.now(), 20)) {
            Workflow w = repo.get(p.workflowId()).orElse(null);
            if (w == null) {
                repo.finishPending(p.id(), "FAILED");
                continue;
            }
            MDC.put("correlationId", w.correlationId());
            try {
                if (KIND_DROITS.equals(p.kind())) replayDroits(w);
                else if (KIND_FICHE.equals(p.kind())) replayFiche(w);
                repo.finishPending(p.id(), "DONE");
                refreshStatus(w.id());
            } catch (RuntimeException e) {
                if (p.attempts() + 1 >= settings.deferredMaxAttempts()) {
                    repo.finishPending(p.id(), "FAILED");
                    raiseAdmin(w, "Tâche différée " + p.kind() + " abandonnée après " + (p.attempts() + 1) + " essais");
                } else {
                    repo.reschedule(p.id(), p.attempts() + 1, settings.deferredDelaySeconds());
                    log.debug("rejeu {} impossible ({}), nouvel essai plus tard", p.kind(), e.toString());
                }
            } finally {
                MDC.remove("correlationId");
            }
        }
    }

    private void replayDroits(Workflow w) {
        DroitsOutcome o = ehr.verifierDroits(w.patientId(), settings.defaultActe(), w.correlationId());
        setStep(w, Model.DROITS, "SUCCEEDED", 1, "vérifié après coup : " + describeDroits(w, o));
        log.info("droits vérifiés après coup pour {}", w.patientId());
    }

    private void replayFiche(Workflow w) {
        Map<String, Object> fiche = buildFiche(w, new AtomicInteger());
        repo.setDossier(w.id(), fiche);
        setStep(w, Model.FICHE, "SUCCEEDED", 1, "fiche rejouée et envoyée au médecin" + niveau(fiche));
        log.info("fiche rejouée pour {}", w.patientId());
    }

    // =================================================================== compensation (saga)

    /**
     * Annule une intervention : compense ce qui peut l'être (le rendez-vous d'urgence). L'envoi d'une ambulance ne
     * peut pas être rappelé (aucun endpoint côté REST) : elle se libère automatiquement à ETA + 10 min.
     */
    public Workflow cancel(String workflowId) {
        Workflow w = repo.get(workflowId).orElseThrow(() -> new NotFoundException("Intervention introuvable : " + workflowId));
        List<String> compensations = new ArrayList<>();
        if (w.appointmentId() != null) {
            try {
                portal.cancelAppointment(w.appointmentId(), w.correlationId());
                compensations.add("rendez-vous d'urgence " + w.appointmentId() + " annulé");
            } catch (RuntimeException e) {
                compensations.add("ANNULATION DU RENDEZ-VOUS " + w.appointmentId() + " IMPOSSIBLE (" + e.getMessage() + ")");
            }
        }
        if (w.ambulanceId() != null) {
            compensations.add("ambulance " + w.ambulanceId() + " non rappelable : libération automatique à ETA + 10 min");
        }
        repo.cancelPending(workflowId);
        raiseAdmin(w, "Intervention annulée" + (compensations.isEmpty() ? "" : " (" + String.join(" ; ", compensations) + ")"));
        repo.setStatus(workflowId, "FAILED", w.endedAt() != null ? w.endedAt() : Instant.now());
        Workflow done = repo.get(workflowId).orElseThrow();
        hub.publish(workflowId, "WORKFLOW_ENDED", done);
        return done;
    }

    // =================================================================== utilitaires

    private void setStep(Workflow w, String name, String status, int attempts, String detail) {
        repo.updateStep(w.id(), name, status, attempts, detail);
        metrics.counter("orchestrator_step_total", "step", name, "status", status).increment();
        hub.publish(w.id(), "STEP_UPDATED", Map.of("workflowId", w.id(), "step", new Step(name, status, attempts, detail)));
    }

    private void raiseAdmin(Workflow w, String message) {
        repo.appendAdminAlert(w.id(), message);
        metrics.counter("orchestrator_admin_alerts_total").increment();
        hub.publish(w.id(), "ADMIN_ALERT", Map.of("workflowId", w.id(), "message", message));
        log.warn("ALERTE ADMIN : {}", message);
    }

    /** Essais répétés (pannes uniquement) derrière un disjoncteur. attempts compte les appels réellement faits. */
    private <T> T resilient(CircuitBreaker cb, AtomicInteger attempts, Supplier<T> call) {
        Retry retry = Retry.of(cb.getName(), RetryConfig.custom()
                .maxAttempts(settings.attempts())
                .waitDuration(Duration.ofMillis(settings.waitMs()))
                .retryOnException(e -> e instanceof ServiceUnavailableException)
                .build());
        Supplier<T> counted = () -> {
            attempts.incrementAndGet();
            return call.get();
        };
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, counted)).get();
    }
}
