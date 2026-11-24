"""Vérifie que chaque scénario produit la trajectoire attendue (sans gRPC).
Exécution : python -m unittest test_scenarios -v
"""
import unittest

from scenarios import SCENARIOS, Trajectoire


def news2(m):
    """Réimplémentation indépendante du barème NEWS2, pour vérifier que les scénarios déclenchent bien les alertes."""
    def tranche(x, bornes):  # bornes = [(max_inclus, note), ...]
        for borne, note in bornes:
            if x <= borne:
                return note
        return bornes[-1][1]
    fr = tranche(m["resp_rate"], [(8, 3), (11, 1), (20, 0), (24, 2), (999, 3)])
    sp = tranche(round(m["spo2"]), [(91, 3), (93, 2), (95, 1), (100, 0)])
    pas = tranche(m["systolic_bp"], [(90, 3), (100, 2), (110, 1), (219, 0), (999, 3)])
    fc = tranche(m["heart_rate"], [(40, 3), (50, 1), (90, 0), (110, 1), (130, 2), (999, 3)])
    t = tranche(m["temperature"], [(35.0, 3), (36.0, 1), (38.0, 0), (39.0, 1), (99, 2)])
    return fr + sp + (2 if m["on_oxygen"] else 0) + pas + fc + (0 if m["consciousness"] == "ALERT" else 3) + t


def serie(patient, scenario, n=70, graine=42):
    tr = Trajectoire(patient, scenario, graine)
    return [tr.mesure(i) for i in range(n)]


class ScenariosTest(unittest.TestCase):
    def test_reproductible_avec_la_meme_graine(self):
        self.assertEqual(serie("PAT-000004", "crise_cardiaque"), serie("PAT-000004", "crise_cardiaque"))

    def test_graine_differente_donne_des_mesures_differentes(self):
        self.assertNotEqual(serie("PAT-000004", "normal", graine=1), serie("PAT-000004", "normal", graine=2))

    def test_patients_differents_ont_des_constantes_differentes(self):
        self.assertNotEqual(serie("PAT-000001", "normal"), serie("PAT-000002", "normal"))

    def test_normal_ne_depasse_jamais_un_score_bas(self):
        for pid in ("PAT-000001", "PAT-000004", "PAT-000010", "PAT-000020"):
            self.assertLessEqual(max(news2(m) for m in serie(pid, "normal", 200)), 2, pid)

    def test_crise_cardiaque_atteint_le_seuil_critique(self):
        scores = [news2(m) for m in serie("PAT-000004", "crise_cardiaque")]
        self.assertLessEqual(max(scores[:8]), 2, "début stable")
        self.assertGreaterEqual(max(scores), 7)
        premier = next(i for i, s in enumerate(scores) if s >= 7)
        self.assertLess(premier, 40, "l'alerte critique doit venir en moins de 40 mesures")

    def test_crise_cardiaque_depasse_aussi_un_seuil_absolu(self):
        derniere = serie("PAT-000004", "crise_cardiaque")[-1]
        self.assertTrue(derniere["spo2"] < 90 or derniere["heart_rate"] > 140 or derniere["systolic_bp"] < 85)

    def test_detresse_respiratoire_monte_progressivement_puis_devient_critique(self):
        scores = [news2(m) for m in serie("PAT-000002", "detresse_respiratoire")]
        self.assertLessEqual(max(scores[:8]), 2)
        self.assertTrue(any(3 <= s <= 6 for s in scores), "une phase intermédiaire (tendance) doit exister")
        self.assertGreaterEqual(max(scores), 7)
        self.assertTrue(serie("PAT-000002", "detresse_respiratoire")[-1]["on_oxygen"])

    def test_chute_signale_la_chute_puis_revient_au_calme(self):
        mesures = serie("PAT-000012", "chute")
        chutes = [i for i, m in enumerate(mesures) if m["fall_detected"]]
        self.assertEqual(chutes, [11, 12])
        self.assertEqual(mesures[15]["consciousness"], "CONFUSION")
        self.assertEqual(mesures[50]["consciousness"], "ALERT")
        self.assertLessEqual(max(news2(m) for m in mesures[45:]), 2)

    def test_valeurs_dans_les_limites_acceptees_par_le_serveur(self):
        for nom in SCENARIOS:
            for m in serie("PAT-000004", nom, 120):
                self.assertTrue(0 <= m["heart_rate"] <= 300 and 0 <= m["resp_rate"] <= 80)
                self.assertTrue(0 <= m["spo2"] <= 100 and 0 <= m["systolic_bp"] <= 350)
                self.assertTrue(0 <= m["diastolic_bp"] <= 250 and 25 <= m["temperature"] <= 45)

    def test_scenario_inconnu(self):
        with self.assertRaises(ValueError):
            Trajectoire("PAT-000004", "inconnu")


if __name__ == "__main__":
    unittest.main()
