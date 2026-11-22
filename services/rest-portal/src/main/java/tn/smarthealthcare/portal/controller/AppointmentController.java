package tn.smarthealthcare.portal.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.smarthealthcare.portal.Dtos.Creneau;
import tn.smarthealthcare.portal.Dtos.RendezVous;
import tn.smarthealthcare.portal.Dtos.RendezVousCreation;
import tn.smarthealthcare.portal.Dtos.RendezVousModification;
import tn.smarthealthcare.portal.service.AppointmentService;
import tn.smarthealthcare.portal.web.Acces;

@RestController
public class AppointmentController {

    private static final String ROLE = "X-User-Role";
    private static final String PATIENT = "X-Patient-Id";

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @GetMapping("/slots")
    public List<Creneau> creneaux(@RequestParam(required = false) String doctorId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                  LocalDate date) {
        return service.creneauxLibres(doctorId, date);
    }

    @GetMapping("/appointments")
    public List<RendezVous> lister(@RequestParam String patientId,
                                   @RequestHeader(value = ROLE, required = false) String role,
                                   @RequestHeader(value = PATIENT, required = false) String appelant) {
        Acces.verifierPatient(role, appelant, patientId);
        return service.listerParPatient(patientId);
    }

    @PostMapping("/appointments")
    public ResponseEntity<RendezVous> creer(@Valid @RequestBody RendezVousCreation body,
                                            @RequestHeader(value = ROLE, required = false) String role,
                                            @RequestHeader(value = PATIENT, required = false) String appelant) {
        Acces.verifierPatient(role, appelant, body.patientId());
        RendezVous rdv = service.creer(body.patientId(), body.slotId(), body.motif());
        return ResponseEntity.created(URI.create("/appointments/" + rdv.id())).body(rdv);
    }

    @GetMapping("/appointments/{id}")
    public RendezVous consulter(@PathVariable String id,
                                @RequestHeader(value = ROLE, required = false) String role,
                                @RequestHeader(value = PATIENT, required = false) String appelant) {
        RendezVous rdv = service.obtenir(id);
        Acces.verifierPatient(role, appelant, rdv.patientId());
        return rdv;
    }

    @PutMapping("/appointments/{id}")
    public RendezVous modifier(@PathVariable String id, @RequestBody RendezVousModification body,
                               @RequestHeader(value = ROLE, required = false) String role,
                               @RequestHeader(value = PATIENT, required = false) String appelant) {
        Acces.verifierPatient(role, appelant, service.obtenir(id).patientId());
        return service.modifier(id, body);
    }

    @DeleteMapping("/appointments/{id}")
    public ResponseEntity<Void> annuler(@PathVariable String id,
                                        @RequestHeader(value = ROLE, required = false) String role,
                                        @RequestHeader(value = PATIENT, required = false) String appelant) {
        Acces.verifierPatient(role, appelant, service.obtenir(id).patientId());
        service.annuler(id);
        return ResponseEntity.noContent().build();
    }
}
