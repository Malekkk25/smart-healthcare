"""Scénarios de constantes vitales. Pur Python, sans gRPC : testable et reproductible (graine fixe).

Un scénario est une trajectoire décrite par des points-clés (étape, valeur) ; entre deux points-clés
les valeurs sont interpolées linéairement, puis un bruit aléatoire reproductible est ajouté.
"""
import random
import zlib

# Constantes de repos (valeurs normales). Chaque patient en reçoit une variante stable.
BASE = {"hr": 72, "rr": 16, "spo2": 97.5, "sbp": 122, "dbp": 78, "temp": 36.8}

# Bruit (écart maximal) appliqué à chaque mesure
NOISE = {"hr": 3, "rr": 1, "spo2": 0.6, "sbp": 4, "dbp": 3, "temp": 0.1}

SCENARIOS = {
    # Patient stable : aucune alerte attendue
    "normal": {
        "description": "constantes stables, aucune alerte",
        "keyframes": {},
        "consciousness": [(0, "ALERT")],
        "fall_steps": [],
        "oxygen_from": None,
    },
    # Crise cardiaque : tachycardie, chute de tension, baisse de la saturation, puis altération de la conscience.
    # Alerte ELEVEE puis CRITIQUE en une trentaine de mesures.
    "crise_cardiaque": {
        "description": "tachycardie, hypotension, hypoxie puis confusion : alerte CRITIQUE",
        "keyframes": {
            "hr": [(0, 72), (8, 75), (20, 118), (30, 148), (60, 150)],
            "sbp": [(0, 122), (8, 120), (20, 100), (30, 84), (60, 82)],
            "dbp": [(0, 78), (20, 66), (30, 52), (60, 50)],
            "spo2": [(0, 97.5), (8, 97), (20, 94), (30, 90), (60, 88)],
            "rr": [(0, 16), (20, 20), (30, 26), (60, 28)],
        },
        "consciousness": [(0, "ALERT"), (28, "VOICE")],
        "fall_steps": [],
        "oxygen_from": None,
    },
    # Détresse respiratoire : dégradation progressive (tendance) puis alerte critique.
    "detresse_respiratoire": {
        "description": "fréquence respiratoire en hausse, SpO2 en baisse, oxygène : tendance puis CRITIQUE",
        "keyframes": {
            "rr": [(0, 16), (10, 18), (25, 24), (40, 29), (70, 31)],
            "spo2": [(0, 97), (10, 96), (25, 94), (40, 90), (70, 86)],
            "hr": [(0, 74), (25, 100), (40, 124), (70, 130)],
            "temp": [(0, 36.8), (40, 37.4)],
        },
        "consciousness": [(0, "ALERT")],
        "fall_steps": [],
        "oxygen_from": 30,
    },
    # Chute : mesures normales, puis chute détectée avec confusion passagère.
    "chute": {
        "description": "chute détectée, pouls en hausse brutale, confusion passagère : alerte ELEVEE",
        "keyframes": {
            "hr": [(0, 72), (9, 74), (11, 108), (25, 90), (40, 74)],
            "sbp": [(0, 122), (11, 138), (25, 126), (40, 122)],
        },
        "consciousness": [(0, "ALERT"), (11, "CONFUSION"), (26, "ALERT")],
        "fall_steps": [11, 12],
        "oxygen_from": None,
    },
}


def interpoler(points, step, defaut):
    """Valeur interpolée à `step` à partir de points-clés triés ; `defaut` s'il n'y en a pas."""
    if not points:
        return defaut
    if step <= points[0][0]:
        return points[0][1]
    for (s0, v0), (s1, v1) in zip(points, points[1:]):
        if step <= s1:
            return v0 + (v1 - v0) * (step - s0) / (s1 - s0)
    return points[-1][1]


def etat(points, step):
    """Valeur « en escalier » (conscience) à `step`."""
    courant = points[0][1]
    for s, v in points:
        if step >= s:
            courant = v
    return courant


def base_patient(patient_id, graine):
    """Constantes de repos propres au patient, stables d'une exécution à l'autre."""
    rng = random.Random(zlib.crc32(patient_id.encode()) ^ graine)
    return {
        "hr": BASE["hr"] + rng.randint(-6, 6),
        "rr": BASE["rr"] + rng.randint(-1, 1),
        "spo2": BASE["spo2"] + rng.uniform(-0.8, 0.8),
        "sbp": BASE["sbp"] + rng.randint(-8, 8),
        "dbp": BASE["dbp"] + rng.randint(-5, 5),
        "temp": BASE["temp"] + rng.uniform(-0.2, 0.2),
    }


class Trajectoire:
    """Génère les mesures successives d'un patient pour un scénario."""

    def __init__(self, patient_id, scenario, graine=42):
        if scenario not in SCENARIOS:
            raise ValueError(f"scénario inconnu : {scenario} (disponibles : {', '.join(SCENARIOS)})")
        self.patient_id = patient_id
        self.scenario = scenario
        self.spec = SCENARIOS[scenario]
        self.base = base_patient(patient_id, graine)
        self.rng = random.Random(zlib.crc32(f"{patient_id}:{scenario}".encode()) ^ graine)

    def mesure(self, step):
        v = {}
        for champ, repos in self.base.items():
            # sans points-clés pour ce champ : constantes de repos du patient ; sinon trajectoire du scénario
            valeur = interpoler(self.spec["keyframes"].get(champ), step, repos)
            valeur += self.rng.uniform(-NOISE[champ], NOISE[champ])
            v[champ] = valeur
        oxygene = self.spec["oxygen_from"] is not None and step >= self.spec["oxygen_from"]
        return {
            "patient_id": self.patient_id,
            "heart_rate": int(round(v["hr"])),
            "resp_rate": int(round(v["rr"])),
            "spo2": round(min(100.0, v["spo2"]), 1),
            "systolic_bp": int(round(v["sbp"])),
            "diastolic_bp": int(round(v["dbp"])),
            "temperature": round(v["temp"], 1),
            "on_oxygen": oxygene,
            "consciousness": etat(self.spec["consciousness"], step),
            "fall_detected": step in self.spec["fall_steps"],
        }
