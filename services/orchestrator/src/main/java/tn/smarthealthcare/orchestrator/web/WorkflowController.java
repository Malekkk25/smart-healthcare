package tn.smarthealthcare.orchestrator.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tn.smarthealthcare.orchestrator.EventHub;
import tn.smarthealthcare.orchestrator.Model.Workflow;
import tn.smarthealthcare.orchestrator.WorkflowEngine;
import tn.smarthealthcare.orchestrator.WorkflowEngine.NotFoundException;
import tn.smarthealthcare.orchestrator.WorkflowRepository;

/** API de contracts/workflow-api.md (accessible par la Gateway sous /api/orchestrator). */
@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    public record StartRequest(@NotBlank String alertId,
                               @NotBlank @Pattern(regexp = "^PAT-[0-9]{6}$", message = "format attendu PAT-000123") String patientId) {}

    private final WorkflowEngine engine;
    private final WorkflowRepository repo;
    private final EventHub hub;

    public WorkflowController(WorkflowEngine engine, WorkflowRepository repo, EventHub hub) {
        this.engine = engine;
        this.repo = repo;
        this.hub = hub;
    }

    @GetMapping
    public List<Workflow> list(@RequestParam(required = false) String status, @RequestParam(required = false) String patientId,
                               @RequestParam(defaultValue = "50") int limit) {
        return repo.list(status, patientId, Math.max(1, Math.min(limit, 200)));
    }

    /** SSE de toutes les interventions (page admin). Déclarée avant /{id}. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter allEvents() {
        return hub.subscribeAll();
    }

    @GetMapping("/{id}")
    public Workflow get(@PathVariable String id) {
        return repo.get(id).orElseThrow(() -> new NotFoundException("Intervention introuvable : " + id));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        repo.get(id).orElseThrow(() -> new NotFoundException("Intervention introuvable : " + id));
        return hub.subscribe(id);
    }

    /** Journal d'exécution : une ligne par changement d'état de chaque étape. */
    @GetMapping("/{id}/journal")
    public List<Map<String, Object>> journal(@PathVariable String id) {
        repo.get(id).orElseThrow(() -> new NotFoundException("Intervention introuvable : " + id));
        return repo.journal(id);
    }

    /** Déclenchement manuel (tests). 202 si créé, 200 avec l'intervention existante pour un alertId déjà connu. */
    @PostMapping
    public ResponseEntity<Workflow> start(@Valid @RequestBody StartRequest body) {
        var started = engine.start(body.alertId(), body.patientId(), "CRITIQUE", 0, "déclenchement manuel");
        return ResponseEntity.status(started.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(started.workflow());
    }

    /** Compensation : annule le rendez-vous d'urgence éventuel et clôt l'intervention. */
    @PostMapping("/{id}/cancel")
    public Workflow cancel(@PathVariable String id) {
        return engine.cancel(id);
    }
}
