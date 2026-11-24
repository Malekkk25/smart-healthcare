package tn.smarthealthcare.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tn.smarthealthcare.telemetry.AlertPolicy.Decision;
import tn.smarthealthcare.telemetry.AlertPolicy.Level;

/** Barème NEWS2 (un test par ligne du tableau, avec les cas limites) et règles d'alerte. */
class News2AndPolicyTest {

    private static News2.Vitals normal() {
        return new News2.Vitals(70, 16, 98, 120, 36.8, false, true);
    }

    // ------------------------------------------------------------------ barème

    @Test
    void constantesNormalesDonnentZero() {
        var r = News2.score(normal());
        assertEquals(0, r.total());
        assertEquals(0, r.maxParam());
    }

    @Test
    void frequenceRespiratoire() {
        assertEquals(3, News2.respRate(8));
        assertEquals(1, News2.respRate(9));
        assertEquals(1, News2.respRate(11));
        assertEquals(0, News2.respRate(12));
        assertEquals(0, News2.respRate(20));
        assertEquals(2, News2.respRate(21));
        assertEquals(2, News2.respRate(24));
        assertEquals(3, News2.respRate(25));
    }

    @Test
    void saturationEnOxygene() {
        assertEquals(3, News2.spo2(91));
        assertEquals(2, News2.spo2(92));
        assertEquals(2, News2.spo2(93));
        assertEquals(1, News2.spo2(94));
        assertEquals(1, News2.spo2(95));
        assertEquals(0, News2.spo2(96));
    }

    @Test
    void pressionSystolique() {
        assertEquals(3, News2.systolic(90));
        assertEquals(2, News2.systolic(91));
        assertEquals(2, News2.systolic(100));
        assertEquals(1, News2.systolic(101));
        assertEquals(1, News2.systolic(110));
        assertEquals(0, News2.systolic(111));
        assertEquals(0, News2.systolic(219));
        assertEquals(3, News2.systolic(220));
    }

    @Test
    void pouls() {
        assertEquals(3, News2.pulse(40));
        assertEquals(1, News2.pulse(41));
        assertEquals(1, News2.pulse(50));
        assertEquals(0, News2.pulse(51));
        assertEquals(0, News2.pulse(90));
        assertEquals(1, News2.pulse(91));
        assertEquals(1, News2.pulse(110));
        assertEquals(2, News2.pulse(111));
        assertEquals(2, News2.pulse(130));
        assertEquals(3, News2.pulse(131));
    }

    @Test
    void temperature() {
        assertEquals(3, News2.temperature(35.0));
        assertEquals(1, News2.temperature(35.1));
        assertEquals(1, News2.temperature(36.0));
        assertEquals(0, News2.temperature(36.1));
        assertEquals(0, News2.temperature(38.0));
        assertEquals(1, News2.temperature(38.1));
        assertEquals(1, News2.temperature(39.0));
        assertEquals(2, News2.temperature(39.1));
    }

    @Test
    void oxygeneSupplementaireVautDeux() {
        var v = new News2.Vitals(70, 16, 98, 120, 36.8, true, true);
        assertEquals(2, News2.score(v).total());
    }

    @Test
    void conscienceAlterceeVautTrois() {
        var v = new News2.Vitals(70, 16, 98, 120, 36.8, false, false);
        var r = News2.score(v);
        assertEquals(3, r.total());
        assertEquals(3, r.maxParam());
    }

    @Test
    void exempleDeCriseCardiaque() {
        // pouls 148 (3) + FR 27 (3) + SpO2 90 (3) = 9
        var r = News2.score(new News2.Vitals(148, 27, 90, 120, 37.0, false, true));
        assertEquals(9, r.total());
    }

    // ------------------------------------------------------------------ règles d'alerte

    private static Decision evaluer(News2.Vitals v, boolean chute, List<Integer> precedents) {
        return AlertPolicy.evaluate(v, News2.score(v), chute, precedents);
    }

    @Test
    void pasDAlerteSiTout_EstNormal() {
        assertNull(evaluer(normal(), false, List.of(0, 0, 0)).severity());
    }

    @Test
    void scoreDeSeptOuPlusEstCritique() {
        var d = evaluer(new News2.Vitals(125, 22, 93, 105, 36.8, false, true), false, List.of()); // 2+2+2+1 = 7
        assertEquals(Level.CRITIQUE, d.severity());
        assertTrue(d.reason().startsWith("NEWS2 = 7"));
    }

    @Test
    void scoreDeCinqOuSixEstEleve() {
        var d = evaluer(new News2.Vitals(115, 22, 94, 120, 36.8, false, true), false, List.of()); // 2+2+1 = 5
        assertEquals(Level.ELEVEE, d.severity());
    }

    @Test
    void unSeulParametreExtremeEstMoyenne() {
        var d = evaluer(new News2.Vitals(70, 8, 98, 120, 36.8, false, true), false, List.of()); // FR 8 = 3, total 3
        assertEquals(Level.MOYENNE, d.severity());
    }

    @Test
    void seuilAbsoluSpo2EstCritiqueMemeSiLeTotalEstBas() {
        var v = new News2.Vitals(70, 16, 89, 120, 36.8, false, true); // total 3 seulement
        var d = evaluer(v, false, List.of());
        assertEquals(Level.CRITIQUE, d.severity());
        assertTrue(d.reason().contains("seuil dépassé"));
    }

    @Test
    void seuilAbsoluPoulsEtPression() {
        assertEquals(Level.CRITIQUE, evaluer(new News2.Vitals(141, 16, 98, 120, 36.8, false, true), false, List.of()).severity());
        assertEquals(Level.CRITIQUE, evaluer(new News2.Vitals(39, 16, 98, 120, 36.8, false, true), false, List.of()).severity());
        assertEquals(Level.CRITIQUE, evaluer(new News2.Vitals(70, 16, 98, 84, 36.8, false, true), false, List.of()).severity());
    }

    @Test
    void uneChuteDonneAuMoinsEleve() {
        var d = evaluer(normal(), true, List.of());
        assertEquals(Level.ELEVEE, d.severity());
        assertTrue(d.reason().contains("chute"));
    }

    @Test
    void degradationProgressiveSansSeuilCritique() {
        // FR 22 (2) + température 38,5 (1) = 3, après des scores de 0 à 1 : hausse de 3 points
        var v = new News2.Vitals(70, 22, 98, 120, 38.5, false, true);
        var d = evaluer(v, false, List.of(0, 0, 1, 1, 1));
        assertEquals(Level.MOYENNE, d.severity());
        assertTrue(d.trendWorsening());
        assertTrue(d.reason().contains("dégradation progressive"));
    }

    @Test
    void unePetiteHausseNEstPasUneTendance() {
        var v = new News2.Vitals(70, 22, 98, 120, 36.8, false, true); // total 2
        var d = evaluer(v, false, List.of(1, 1, 1));
        assertNull(d.severity());
        assertEquals(false, d.trendWorsening());
    }
}
