package tn.smarthealthcare.portal.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tn.smarthealthcare.portal.Dtos.Medicament;
import tn.smarthealthcare.portal.Dtos.Ordonnance;
import tn.smarthealthcare.portal.Dtos.Patient;
import tn.smarthealthcare.portal.Dtos.PatientModification;
import tn.smarthealthcare.portal.web.ApiException;

@Repository
public class PatientRepository {

    private static final String SELECT = """
            SELECT id, nom, prenom, sexe, date_naissance, service, pathologie, niveau_risque, numero_assure,
                   taux_remboursement FROM patient""";

    private static final RowMapper<Patient> MAPPER = (rs, i) -> new Patient(
            rs.getString("id"), rs.getString("nom"), rs.getString("prenom"), rs.getString("sexe"),
            rs.getDate("date_naissance").toLocalDate(), rs.getString("service"), rs.getString("pathologie"),
            rs.getString("niveau_risque"), rs.getString("numero_assure"),
            (Integer) rs.getObject("taux_remboursement"));

    private final JdbcTemplate jdbc;

    public PatientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Patient> lister(String service, String pathologie, String niveauRisque) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (service != null && !service.isBlank()) {
            sql.append(" AND lower(service) = lower(?)");
            args.add(service);
        }
        if (pathologie != null && !pathologie.isBlank()) {
            sql.append(" AND lower(pathologie) = lower(?)");
            args.add(pathologie);
        }
        if (niveauRisque != null && !niveauRisque.isBlank()) {
            sql.append(" AND niveau_risque = ?");
            args.add(niveauRisque.toUpperCase());
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<Patient> trouver(String id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Patient obtenir(String id) {
        return trouver(id).orElseThrow(
                () -> ApiException.introuvable("Aucun patient avec l'identifiant " + id + "."));
    }

    public Patient modifier(String id, PatientModification m) {
        obtenir(id);
        jdbc.update("""
                UPDATE patient SET nom = ?, prenom = ?, sexe = ?, date_naissance = ?,
                       service = COALESCE(?, service), pathologie = COALESCE(?, pathologie)
                WHERE id = ?""",
                m.nom(), m.prenom(), m.sexe(), java.sql.Date.valueOf(m.dateNaissance()), m.service(), m.pathologie(),
                id);
        return obtenir(id);
    }

    public List<Ordonnance> ordonnances(String patientId) {
        obtenir(patientId);
        return jdbc.query("SELECT id, patient_id, doctor_id, date FROM ordonnance WHERE patient_id = ? "
                        + "ORDER BY date DESC, id DESC",
                (rs, i) -> new Ordonnance(rs.getString("id"), rs.getString("patient_id"), rs.getString("doctor_id"),
                        rs.getDate("date").toLocalDate(), medicaments(rs.getString("id"))),
                patientId);
    }

    private List<Medicament> medicaments(String ordonnanceId) {
        return jdbc.query("SELECT nom, posologie, duree_jours FROM medicament WHERE ordonnance_id = ? ORDER BY ordre",
                (rs, i) -> new Medicament(rs.getString("nom"), rs.getString("posologie"),
                        (Integer) rs.getObject("duree_jours")),
                ordonnanceId);
    }
}
