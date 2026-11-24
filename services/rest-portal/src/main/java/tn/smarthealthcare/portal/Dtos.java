package tn.smarthealthcare.portal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Objets échangés par l'API (conformes à contracts/portal.openapi.yaml). */
public final class Dtos {

    private Dtos() {}

    public static final String PATIENT_ID_REGEX = "^PAT-[0-9]{6}$";

    public record Creneau(String id, String doctorId, Instant debut, Instant fin, boolean urgence) {}

    public record RendezVousCreation(
            @NotBlank @Pattern(regexp = PATIENT_ID_REGEX, message = "format attendu PAT-000123") String patientId,
            @NotBlank String slotId,
            String motif) {}

    public record RendezVousModification(String slotId, String motif) {}

    public record RendezVous(String id, String patientId, String slotId, String doctorId, Instant debut, String motif,
                             String statut,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String visioUrl) {}

    public record Patient(String id, String nom, String prenom, String sexe, LocalDate dateNaissance, String service,
                          String pathologie, String niveauRisque, String numeroAssure, Integer tauxRemboursement) {}

    public record PatientModification(
            @NotBlank String nom,
            @NotBlank String prenom,
            @NotBlank @Pattern(regexp = "^[MF]$", message = "M ou F") String sexe,
            @NotNull LocalDate dateNaissance,
            String service,
            String pathologie) {}

    public record Medicament(String nom, String posologie, Integer dureeJours) {}

    public record Ordonnance(String id, String patientId, String doctorId, LocalDate date,
                             List<Medicament> medicaments) {}

    public record Position(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    public record Ambulance(String id, String statut, Position position) {}

    public record Localisation(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            String adresse) {}

    public record UrgenceDemande(
            @NotBlank @Pattern(regexp = PATIENT_ID_REGEX, message = "format attendu PAT-000123") String patientId,
            @NotBlank String alertId,
            @NotBlank @Pattern(regexp = "FAIBLE|MOYENNE|ELEVEE|CRITIQUE",
                    message = "FAIBLE, MOYENNE, ELEVEE ou CRITIQUE") String severity,
            @NotNull @Valid Localisation location) {}

    public record UrgenceReponse(
            String dispatchId,
            String ambulanceId,
            Integer etaMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String fallbackAppointmentId) {}
}
