package tn.smarthealthcare.portal.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.smarthealthcare.portal.Dtos.Ordonnance;
import tn.smarthealthcare.portal.Dtos.Patient;
import tn.smarthealthcare.portal.Dtos.PatientModification;
import tn.smarthealthcare.portal.service.PatientRepository;
import tn.smarthealthcare.portal.web.Acces;

@RestController
public class PatientController {

    private final PatientRepository patients;

    public PatientController(PatientRepository patients) {
        this.patients = patients;
    }

    @GetMapping("/patients")
    public List<Patient> lister(@RequestParam(required = false) String service,
                                @RequestParam(required = false) String pathologie,
                                @RequestParam(required = false) String niveauRisque) {
        return patients.lister(service, pathologie, niveauRisque);
    }

    @GetMapping("/patients/{id}")
    public Patient consulter(@PathVariable String id,
                             @RequestHeader(value = "X-User-Role", required = false) String role,
                             @RequestHeader(value = "X-Patient-Id", required = false) String patientIdAppelant) {
        Acces.verifierPatient(role, patientIdAppelant, id);
        return patients.obtenir(id);
    }

    @PutMapping("/patients/{id}")
    public Patient modifier(@PathVariable String id, @Valid @RequestBody PatientModification body,
                            @RequestHeader(value = "X-User-Role", required = false) String role,
                            @RequestHeader(value = "X-Patient-Id", required = false) String patientIdAppelant) {
        Acces.verifierPatient(role, patientIdAppelant, id);
        return patients.modifier(id, body);
    }

    @GetMapping("/patients/{id}/prescriptions")
    public List<Ordonnance> ordonnances(@PathVariable String id,
                                        @RequestHeader(value = "X-User-Role", required = false) String role,
                                        @RequestHeader(value = "X-Patient-Id", required = false) String patientIdAppelant) {
        Acces.verifierPatient(role, patientIdAppelant, id);
        return patients.ordonnances(id);
    }
}
