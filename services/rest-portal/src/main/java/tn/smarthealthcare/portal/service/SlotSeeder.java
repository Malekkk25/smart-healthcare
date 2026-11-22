package tn.smarthealthcare.portal.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Génère à chaque démarrage les créneaux des 14 prochains jours (idempotent : identifiants déterministes).
 * Créneaux normaux de 30 min (08:00-11:30 et 13:00-16:30 UTC) et 2 créneaux d'urgence par jour et par médecin.
 */
@Component
public class SlotSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlotSeeder.class);
    private static final List<String> MEDECINS = List.of("DOC-001", "DOC-002", "DOC-003");
    private static final int JOURS = 14;
    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final JdbcTemplate jdbc;

    public SlotSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Object[]> lignes = new ArrayList<>();
        LocalDate debut = LocalDate.now(ZoneOffset.UTC);
        for (int j = 0; j < JOURS; j++) {
            LocalDate jour = debut.plusDays(j);
            for (String medecin : MEDECINS) {
                for (LocalTime t = LocalTime.of(8, 0); t.isBefore(LocalTime.of(12, 0)); t = t.plusMinutes(30)) {
                    lignes.add(ligne(jour, t, medecin, false));
                }
                for (LocalTime t = LocalTime.of(13, 0); t.isBefore(LocalTime.of(17, 0)); t = t.plusMinutes(30)) {
                    lignes.add(ligne(jour, t, medecin, false));
                }
                lignes.add(ligne(jour, LocalTime.of(17, 0), medecin, true));
                lignes.add(ligne(jour, LocalTime.of(17, 30), medecin, true));
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO creneau (id, doctor_id, debut, fin, urgence) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""", lignes);
        log.info("Créneaux vérifiés/générés : {} lignes sur {} jours", lignes.size(), JOURS);
    }

    private static Object[] ligne(LocalDate jour, LocalTime heure, String medecin, boolean urgence) {
        var debut = jour.atTime(heure).toInstant(ZoneOffset.UTC);
        var fin = debut.plusSeconds(30 * 60);
        String id = "SLOT-" + ID_FMT.format(jour.atTime(heure)) + "-" + medecin;
        return new Object[] {id, medecin, Timestamp.from(debut), Timestamp.from(fin), urgence};
    }
}
