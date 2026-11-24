package tn.smarthealthcare.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Règles de décision : une mesure déclenche-t-elle une alerte, et de quelle gravité ? Fonction pure. */
public final class AlertPolicy {

    private AlertPolicy() {}

    /** Gravité d'alerte (mêmes mots que le REST). Ne pas confondre avec le niveau de risque du patient. */
    public enum Level {
        FAIBLE, MOYENNE, ELEVEE, CRITIQUE;

        public static Level max(Level a, Level b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.compareTo(b) >= 0 ? a : b;
        }
    }

    /** @param severity null = aucune alerte */
    public record Decision(Level severity, String reason, boolean trendWorsening) {}

    /** Nombre de scores précédents examinés pour détecter une dégradation progressive. */
    static final int TREND_WINDOW = 5;
    /** Hausse minimale du score NEWS2 sur la fenêtre pour parler de dégradation progressive. */
    static final int TREND_RISE = 2;

    /**
     * @param previousScores scores NEWS2 précédents du patient, du plus ancien au plus récent (sans la mesure courante)
     */
    public static Decision evaluate(News2.Vitals v, News2.Result r, boolean fallDetected, List<Integer> previousScores) {
        Level severity = null;
        List<String> motifs = new ArrayList<>();

        // Seuils absolus : critiques même si le total NEWS2 est plus bas
        List<String> seuils = new ArrayList<>();
        if (Math.round(v.spo2()) < 90) seuils.add("SpO2 " + Math.round(v.spo2()) + " %");
        if (v.heartRate() > 140) seuils.add("pouls " + v.heartRate());
        if (v.heartRate() < 40) seuils.add("pouls " + v.heartRate());
        if (v.systolicBp() < 85) seuils.add("PAS " + v.systolicBp());
        if (!seuils.isEmpty()) {
            severity = Level.CRITIQUE;
            motifs.add("seuil dépassé (" + String.join(", ", seuils) + ")");
        }

        if (r.total() >= 7) severity = Level.max(severity, Level.CRITIQUE);
        else if (r.total() >= 5) severity = Level.max(severity, Level.ELEVEE);
        else if (r.maxParam() >= 3) severity = Level.max(severity, Level.MOYENNE);

        if (fallDetected) {
            severity = Level.max(severity, Level.ELEVEE);
            motifs.add("chute détectée");
        }
        if (!v.conscious()) motifs.add("altération de la conscience");

        boolean trend = false;
        int depart = 0;
        if (!previousScores.isEmpty()) {
            List<Integer> fenetre = previousScores.subList(Math.max(0, previousScores.size() - TREND_WINDOW), previousScores.size());
            depart = fenetre.stream().mapToInt(Integer::intValue).min().orElse(r.total());
            trend = r.total() - depart >= TREND_RISE;
        }
        if (trend && severity == null && r.total() >= 3) {
            severity = Level.MOYENNE;
            motifs.add("dégradation progressive : NEWS2 " + depart + " → " + r.total());
        }
        if (severity == null) return new Decision(null, null, trend);

        String detail = detailScores(r.detail());
        String reason = "NEWS2 = " + r.total() + (detail.isEmpty() ? "" : " (" + detail + ")")
                + (motifs.isEmpty() ? "" : " ; " + String.join(" ; ", motifs));
        return new Decision(severity, reason, trend);
    }

    /** Liste les paramètres anormaux, par exemple « FR 2, SpO2 3 » (note de chaque paramètre). */
    private static String detailScores(Map<String, Integer> detail) {
        List<String> parts = new ArrayList<>();
        detail.forEach((nom, note) -> { if (note > 0) parts.add(nom + " +" + note); });
        return String.join(", ", parts);
    }
}
