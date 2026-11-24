package tn.smarthealthcare.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Score NEWS2 (National Early Warning Score 2) : somme de 7 paramètres notés de 0 à 3
 * (supplément d'oxygène : 0 ou 2). Fonction pure, sans dépendance, entièrement testée.
 */
public final class News2 {

    private News2() {}

    /** Constantes utiles au score (SpO2 échelle 1). */
    public record Vitals(int heartRate, int respRate, double spo2, int systolicBp, double temperature,
                         boolean onOxygen, boolean conscious) {}

    /**
     * @param total    score total
     * @param maxParam plus grande note individuelle (3 = un paramètre extrême)
     * @param detail   note de chaque paramètre
     */
    public record Result(int total, int maxParam, Map<String, Integer> detail) {}

    public static Result score(Vitals v) {
        Map<String, Integer> d = new LinkedHashMap<>();
        d.put("FR", respRate(v.respRate()));
        d.put("SpO2", spo2(v.spo2()));
        d.put("O2", v.onOxygen() ? 2 : 0);
        d.put("PAS", systolic(v.systolicBp()));
        d.put("pouls", pulse(v.heartRate()));
        d.put("conscience", v.conscious() ? 0 : 3);
        d.put("T°", temperature(v.temperature()));
        int total = d.values().stream().mapToInt(Integer::intValue).sum();
        int max = d.entrySet().stream().filter(e -> !e.getKey().equals("O2")).mapToInt(Map.Entry::getValue).max().orElse(0);
        return new Result(total, max, d);
    }

    static int respRate(int fr) {
        if (fr <= 8) return 3;
        if (fr <= 11) return 1;
        if (fr <= 20) return 0;
        if (fr <= 24) return 2;
        return 3;
    }

    static int spo2(double value) {
        long s = Math.round(value);
        if (s <= 91) return 3;
        if (s <= 93) return 2;
        if (s <= 95) return 1;
        return 0;
    }

    static int systolic(int pas) {
        if (pas <= 90) return 3;
        if (pas <= 100) return 2;
        if (pas <= 110) return 1;
        if (pas <= 219) return 0;
        return 3;
    }

    static int pulse(int fc) {
        if (fc <= 40) return 3;
        if (fc <= 50) return 1;
        if (fc <= 90) return 0;
        if (fc <= 110) return 1;
        if (fc <= 130) return 2;
        return 3;
    }

    static int temperature(double t) {
        if (t <= 35.0) return 3;
        if (t <= 36.0) return 1;
        if (t <= 38.0) return 0;
        if (t <= 39.0) return 1;
        return 2;
    }
}
