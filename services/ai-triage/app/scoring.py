"""Score de risque DÉTERMINISTE : c'est la base qui fonctionne toujours, avec ou sans modèle de langage.

Entrée : la fiche d'intervention assemblée par l'orchestrateur (alerte, intervention, patient).
Sortie : un score de 0 à 100 et un niveau de risque (mêmes mots que la gravité des alertes).
"""
from __future__ import annotations

from datetime import date
from typing import Any

# Pathologies pour lesquelles une alerte vitale est plus préoccupante
PATHOLOGIES_A_RISQUE = ("insuffisance cardiaque", "arythmie", "bpco", "asthme")

NIVEAUX = [(80, "CRITIQUE"), (60, "ELEVEE"), (35, "MOYENNE"), (0, "FAIBLE")]


def age(date_naissance: str | None, today: date | None = None) -> int | None:
    """Âge en années à partir d'une date ISO (AAAA-MM-JJ), ou None si absente ou illisible."""
    if not date_naissance:
        return None
    try:
        naissance = date.fromisoformat(str(date_naissance)[:10])
    except ValueError:
        return None
    today = today or date.today()
    return today.year - naissance.year - ((today.month, today.day) < (naissance.month, naissance.day))


def niveau(score: int) -> str:
    for seuil, nom in NIVEAUX:
        if score >= seuil:
            return nom
    return "FAIBLE"


def score_risque(fiche: dict[str, Any], today: date | None = None) -> dict[str, Any]:
    """Calcule le score et détaille les facteurs (utile pour expliquer le résultat au médecin)."""
    alerte = fiche.get("alerte") or {}
    patient = fiche.get("patient") or {}
    facteurs: list[tuple[str, int]] = []

    news2 = int(alerte.get("news2Score") or 0)
    facteurs.append((f"NEWS2 {news2}", min(70, news2 * 8)))

    gravite = alerte.get("severity")
    if gravite == "CRITIQUE":
        facteurs.append(("alerte critique", 10))
    elif gravite == "ELEVEE":
        facteurs.append(("alerte élevée", 5))

    a = age(patient.get("dateNaissance"), today)
    if a is not None:
        if a >= 75:
            facteurs.append((f"âge {a} ans", 10))
        elif a >= 65:
            facteurs.append((f"âge {a} ans", 6))
        elif a >= 50:
            facteurs.append((f"âge {a} ans", 3))

    pathologie = str(patient.get("pathologie") or "").lower()
    if any(p in pathologie for p in PATHOLOGIES_A_RISQUE):
        facteurs.append((f"pathologie : {patient.get('pathologie')}", 6))

    risque = patient.get("niveauRisque")
    if risque == "ELEVE":
        facteurs.append(("dossier à risque élevé", 8))
    elif risque == "MOYEN":
        facteurs.append(("dossier à risque moyen", 4))

    vital = patient.get("latestVital") or {}
    if vital.get("onOxygen"):
        facteurs.append(("sous oxygène", 3))
    if vital.get("consciousness") not in (None, "", "ALERT", "CONSCIOUSNESS_UNSPECIFIED"):
        facteurs.append(("conscience altérée", 5))

    ouvertes = patient.get("alerts") or []
    if len(ouvertes) > 1:
        facteurs.append((f"{len(ouvertes)} alertes ouvertes", 4))
    if any(x.get("trendWorsening") for x in ouvertes):
        facteurs.append(("dégradation progressive", 3))

    total = min(100, sum(points for _, points in facteurs))
    return {"riskScore": total, "riskLevel": niveau(total), "facteurs": [f"{nom} (+{p})" for nom, p in facteurs if p]}
