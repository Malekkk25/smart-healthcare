"""Résumé de la fiche d'intervention : un modèle de langage si disponible, sinon un texte généré par modèle de phrase.

Le repli est OBLIGATOIRE : le médecin reçoit toujours un résumé, même sans clé d'API, sans réseau ou si le modèle est lent.
"""
from __future__ import annotations

import logging
import os
from typing import Any

import httpx

log = logging.getLogger("ai-triage")

API_URL = "https://api.anthropic.com/v1/messages"
DEFAULT_MODEL = "claude-haiku-5-5"
TIMEOUT_SECONDS = 5.0


def resume_modele(fiche: dict[str, Any], score: dict[str, Any]) -> str:
    """Résumé en phrases, généré sans modèle de langage (toujours disponible)."""
    alerte = fiche.get("alerte") or {}
    patient = fiche.get("patient") or {}
    inter = fiche.get("intervention") or {}

    nom = " ".join(x for x in (patient.get("prenom"), patient.get("nom")) if x) or "Patient inconnu"
    contexte = ", ".join(x for x in (patient.get("service"), patient.get("pathologie")) if x)
    phrases = [f"{nom}{f' ({contexte})' if contexte else ''} : alerte {alerte.get('severity', '?')}, "
               f"NEWS2 {alerte.get('news2Score', '?')}"
               f"{' - ' + alerte['reason'] if alerte.get('reason') else ''}."]

    v = patient.get("latestVital") or {}
    if v:
        phrases.append(f"Dernières constantes : pouls {v.get('heartRate', '?')}, FR {v.get('respRate', '?')}, "
                       f"SpO2 {v.get('spo2', '?')} %, PAS {v.get('systolicBp', '?')}, conscience {v.get('consciousness', '?')}.")

    if inter.get("ambulanceId"):
        phrases.append(f"Ambulance {inter['ambulanceId']} en route, arrivée estimée dans {inter.get('etaMinutes', '?')} min.")
    elif inter.get("appointmentId"):
        phrases.append(f"Pas d'ambulance confirmée : créneau d'urgence {inter['appointmentId']} réservé.")
    else:
        phrases.append("Aucun envoi confirmé : intervention manuelle requise.")

    ordonnances = (patient.get("treatment") or {}).get("ordonnances") or []
    medicaments = [m.get("nom") for o in ordonnances for m in o.get("medicaments", []) if m.get("nom")]
    if medicaments:
        phrases.append("Traitement en cours : " + ", ".join(medicaments[:5]) + ".")

    phrases.append(f"Risque évalué : {score['riskLevel']} ({score['riskScore']}/100).")
    return " ".join(phrases)


def resume_llm(fiche: dict[str, Any], score: dict[str, Any], client: httpx.Client | None = None) -> str | None:
    """Résumé par le modèle de langage. None si aucune clé n'est configurée. Lève une exception en cas d'échec."""
    cle = os.environ.get("ANTHROPIC_API_KEY")
    if not cle:
        return None
    modele = os.environ.get("LLM_MODEL", DEFAULT_MODEL)
    consigne = (
        "Tu aides un médecin d'urgence. Rédige en français un résumé de 3 à 4 phrases maximum de cette intervention, "
        "factuel, sans inventer de donnée absente. Commence par le risque et l'action en cours.\n\n"
        f"Score de risque : {score['riskScore']}/100 ({score['riskLevel']}). Facteurs : {', '.join(score['facteurs'])}.\n"
        f"Fiche (JSON) : {fiche}"
    )
    http = client or httpx.Client(timeout=TIMEOUT_SECONDS)
    reponse = http.post(
        API_URL,
        headers={"x-api-key": cle, "anthropic-version": "2023-06-01", "content-type": "application/json"},
        json={"model": modele, "max_tokens": 400, "messages": [{"role": "user", "content": consigne}]},
    )
    reponse.raise_for_status()
    blocs = reponse.json().get("content", [])
    texte = "".join(b.get("text", "") for b in blocs if b.get("type") == "text").strip()
    if not texte:
        raise ValueError("réponse vide du modèle")
    return texte


def resumer(fiche: dict[str, Any], score: dict[str, Any]) -> tuple[str, str]:
    """(résumé, source) : « llm » si le modèle a répondu, « modele-de-phrases » sinon."""
    try:
        texte = resume_llm(fiche, score)
        if texte:
            return texte, "llm"
    except Exception as e:  # réseau, délai, quota, réponse invalide : on ne bloque jamais le médecin
        log.warning("résumé par le modèle indisponible (%s), texte de repli utilisé", e)
    return resume_modele(fiche, score), "modele-de-phrases"
