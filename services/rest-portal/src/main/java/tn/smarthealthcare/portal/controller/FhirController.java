package tn.smarthealthcare.portal.controller;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.smarthealthcare.portal.Dtos.Ordonnance;
import tn.smarthealthcare.portal.Dtos.Patient;
import tn.smarthealthcare.portal.Dtos.RendezVous;
import tn.smarthealthcare.portal.service.AppointmentService;
import tn.smarthealthcare.portal.service.PatientRepository;
import tn.smarthealthcare.portal.web.Acces;

/**
 * Vue en lecture seule inspirée de HL7 FHIR R4 : ressources Patient, Appointment et MedicationRequest.
 * Ce n'est pas une implémentation complète du standard (pas de CapabilityStatement, pas de recherche avancée) :
 * elle montre que les données du portail sont exposables dans un format d'interopérabilité santé.
 */
@RestController
@RequestMapping(value = "/fhir", produces = {"application/fhir+json", "application/json"})
public class FhirController {

    private static final String SYSTEME_ASSURE = "http://smarthealthcare.tn/numero-assure";

    private final PatientRepository patients;
    private final AppointmentService appointments;

    public FhirController(PatientRepository patients, AppointmentService appointments) {
        this.patients = patients;
        this.appointments = appointments;
    }

    @GetMapping("/Patient/{id}")
    public Map<String, Object> patient(@PathVariable String id,
                                       @RequestHeader(value = "X-User-Role", required = false) String role,
                                       @RequestHeader(value = "X-Patient-Id", required = false) String appelant) {
        Acces.verifierPatient(role, appelant, id);
        Patient p = patients.obtenir(id);
        Map<String, Object> r = ressource("Patient", p.id());
        r.put("identifier", List.of(Map.of("system", SYSTEME_ASSURE, "value", String.valueOf(p.numeroAssure()))));
        r.put("name", List.of(Map.of("family", p.nom(), "given", List.of(p.prenom()))));
        r.put("gender", "M".equals(p.sexe()) ? "male" : "female");
        r.put("birthDate", p.dateNaissance().toString());
        return r;
    }

    @GetMapping("/Appointment")
    public Map<String, Object> rendezVous(@RequestParam String patient,
                                          @RequestHeader(value = "X-User-Role", required = false) String role,
                                          @RequestHeader(value = "X-Patient-Id", required = false) String appelant) {
        Acces.verifierPatient(role, appelant, patient);
        List<Map<String, Object>> entrees = new ArrayList<>();
        for (RendezVous rdv : appointments.listerParPatient(patient)) {
            Map<String, Object> r = ressource("Appointment", rdv.id());
            r.put("status", switch (rdv.statut()) {
                case "CONFIRME" -> "booked";
                case "ANNULE" -> "cancelled";
                default -> "fulfilled";
            });
            r.put("start", rdv.debut().toString());
            r.put("end", rdv.debut().plus(30, ChronoUnit.MINUTES).toString());
            if (rdv.motif() != null) r.put("description", rdv.motif());
            r.put("participant", List.of(
                    Map.of("actor", Map.of("reference", "Patient/" + rdv.patientId()), "status", "accepted"),
                    Map.of("actor", Map.of("reference", "Practitioner/" + rdv.doctorId()), "status", "accepted")));
            entrees.add(r);
        }
        return bundle(entrees);
    }

    @GetMapping("/MedicationRequest")
    public Map<String, Object> ordonnances(@RequestParam String patient,
                                           @RequestHeader(value = "X-User-Role", required = false) String role,
                                           @RequestHeader(value = "X-Patient-Id", required = false) String appelant) {
        Acces.verifierPatient(role, appelant, patient);
        List<Map<String, Object>> entrees = new ArrayList<>();
        for (Ordonnance o : patients.ordonnances(patient)) {
            int n = 0;
            for (var m : o.medicaments()) {
                Map<String, Object> r = ressource("MedicationRequest", o.id() + "-" + (++n));
                r.put("status", "active");
                r.put("intent", "order");
                r.put("subject", Map.of("reference", "Patient/" + o.patientId()));
                r.put("authoredOn", o.date().toString());
                r.put("requester", Map.of("reference", "Practitioner/" + o.doctorId()));
                r.put("medicationCodeableConcept", Map.of("text", m.nom()));
                Map<String, Object> dosage = new LinkedHashMap<>();
                dosage.put("text", m.posologie());
                if (m.dureeJours() != null) {
                    dosage.put("timing", Map.of("repeat", Map.of("boundsDuration",
                            Map.of("value", m.dureeJours(), "unit", "jour", "system", "http://unitsofmeasure.org", "code", "d"))));
                }
                r.put("dosageInstruction", List.of(dosage));
                entrees.add(r);
            }
        }
        return bundle(entrees);
    }

    private static Map<String, Object> ressource(String type, String id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", type);
        r.put("id", id);
        return r;
    }

    private static Map<String, Object> bundle(List<Map<String, Object>> ressources) {
        Map<String, Object> b = ressource("Bundle", null);
        b.remove("id");
        b.put("type", "searchset");
        b.put("total", ressources.size());
        b.put("entry", ressources.stream().map(r -> Map.of("resource", r)).toList());
        return b;
    }
}
