package tn.smarthealthcare.portal.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tn.smarthealthcare.portal.Dtos.Ambulance;
import tn.smarthealthcare.portal.Dtos.UrgenceDemande;
import tn.smarthealthcare.portal.Dtos.UrgenceReponse;
import tn.smarthealthcare.portal.service.EmergencyService;

@RestController
public class EmergencyController {

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    @GetMapping("/ambulances")
    public List<Ambulance> ambulances() {
        return service.listerAmbulances();
    }

    @PostMapping("/emergency/dispatch")
    @ResponseStatus(HttpStatus.CREATED)
    public UrgenceReponse dispatch(@Valid @RequestBody UrgenceDemande body) {
        return service.declencher(body);
    }
}
