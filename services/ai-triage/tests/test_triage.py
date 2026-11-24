"""Tests du service IA : score déterministe, repli du résumé, API.  Exécution : python -m pytest tests -q"""
from datetime import date

import httpx
import pytest
from fastapi.testclient import TestClient

from app import summary
from app.main import app
from app.scoring import age, niveau, score_risque

client = TestClient(app)

TODAY = date(2026, 11, 24)

FICHE = {
    "alerte": {"alertId": "a1", "severity": "CRITIQUE", "news2Score": 9, "reason": "NEWS2 = 9 (SpO2 90 %)"},
    "intervention": {"ambulanceId": "AMB-03", "etaMinutes": 7, "appointmentId": None},
    "patient": {
        "id": "PAT-000004", "nom": "Bouzid", "prenom": "Sarra", "dateNaissance": "1946-06-28",
        "service": "cardiologie", "pathologie": "Arythmie", "niveauRisque": "ELEVE",
        "latestVital": {"heartRate": 148, "respRate": 27, "spo2": 90, "systolicBp": 84, "consciousness": "VOICE"},
        "alerts": [{"trendWorsening": False}],
        "treatment": {"ordonnances": [{"medicaments": [{"nom": "Amiodarone 200 mg"}]}]},
    },
}


# ------------------------------------------------------------------ score

def test_age():
    assert age("1946-06-28", TODAY) == 80
    assert age("2000-12-31", TODAY) == 25   # anniversaire pas encore passé
    assert age(None) is None
    assert age("pas-une-date") is None


def test_niveaux():
    assert niveau(100) == "CRITIQUE" and niveau(80) == "CRITIQUE"
    assert niveau(79) == "ELEVEE" and niveau(60) == "ELEVEE"
    assert niveau(59) == "MOYENNE" and niveau(35) == "MOYENNE"
    assert niveau(34) == "FAIBLE" and niveau(0) == "FAIBLE"


def test_patient_a_haut_risque_est_critique():
    r = score_risque(FICHE, TODAY)
    assert r["riskLevel"] == "CRITIQUE"
    assert 80 <= r["riskScore"] <= 100
    assert any("NEWS2 9" in f for f in r["facteurs"])


def test_le_score_est_deterministe():
    assert score_risque(FICHE, TODAY) == score_risque(FICHE, TODAY)


def test_un_patient_jeune_et_stable_a_un_risque_faible():
    fiche = {"alerte": {"severity": "FAIBLE", "news2Score": 1}, "patient": {"dateNaissance": "1999-01-01", "niveauRisque": "FAIBLE"}}
    assert score_risque(fiche, TODAY)["riskLevel"] == "FAIBLE"


def test_le_score_ne_depasse_jamais_100():
    fiche = {"alerte": {"severity": "CRITIQUE", "news2Score": 20}, "patient": FICHE["patient"]}
    assert score_risque(fiche, TODAY)["riskScore"] == 100


def test_une_fiche_vide_ne_plante_pas():
    r = score_risque({}, TODAY)
    assert r["riskScore"] == 0 and r["riskLevel"] == "FAIBLE"


def test_l_age_et_la_pathologie_augmentent_le_score():
    base = {"alerte": {"severity": "ELEVEE", "news2Score": 5}, "patient": {"dateNaissance": "1999-01-01"}}
    age_risque = {"alerte": base["alerte"], "patient": {"dateNaissance": "1940-01-01", "pathologie": "BPCO"}}
    assert score_risque(age_risque, TODAY)["riskScore"] > score_risque(base, TODAY)["riskScore"]


# ------------------------------------------------------------------ résumé

def test_le_resume_de_repli_contient_les_faits_essentiels():
    score = score_risque(FICHE, TODAY)
    texte = summary.resume_modele(FICHE, score)
    for attendu in ("Sarra Bouzid", "cardiologie", "NEWS2 9", "AMB-03", "7 min", "Amiodarone", "CRITIQUE"):
        assert attendu in texte, attendu


def test_le_resume_signale_l_absence_d_envoi():
    fiche = {"alerte": {"severity": "CRITIQUE", "news2Score": 8}, "intervention": {}, "patient": {"nom": "X"}}
    assert "intervention manuelle requise" in summary.resume_modele(fiche, score_risque(fiche, TODAY))


def test_sans_cle_d_api_le_repli_est_utilise(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    texte, source = summary.resumer(FICHE, score_risque(FICHE, TODAY))
    assert source == "modele-de-phrases" and "Sarra Bouzid" in texte


def test_le_modele_de_langage_est_utilise_quand_il_repond(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "cle-de-test")
    appels = {}

    def faux_post(self, url, headers=None, json=None, **kw):
        appels.update(url=url, headers=headers, json=json)
        return httpx.Response(200, json={"content": [{"type": "text", "text": "Résumé rédigé par le modèle."}]},
                              request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx.Client, "post", faux_post)
    texte, source = summary.resumer(FICHE, score_risque(FICHE, TODAY))
    assert (texte, source) == ("Résumé rédigé par le modèle.", "llm")
    assert appels["headers"]["x-api-key"] == "cle-de-test"
    assert appels["json"]["messages"][0]["role"] == "user"


@pytest.mark.parametrize("panne", [
    httpx.ConnectTimeout("délai"),
    httpx.ConnectError("réseau"),
])
def test_une_panne_du_modele_n_empeche_jamais_le_resume(monkeypatch, panne):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "cle-de-test")

    def en_panne(self, url, **kw):
        raise panne

    monkeypatch.setattr(httpx.Client, "post", en_panne)
    texte, source = summary.resumer(FICHE, score_risque(FICHE, TODAY))
    assert source == "modele-de-phrases" and "Sarra Bouzid" in texte


def test_une_erreur_http_du_modele_declenche_le_repli(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "cle-de-test")
    monkeypatch.setattr(httpx.Client, "post", lambda self, url, **kw: httpx.Response(
        529, json={"error": "surcharge"}, request=httpx.Request("POST", url)))
    assert summary.resumer(FICHE, score_risque(FICHE, TODAY))[1] == "modele-de-phrases"


def test_une_reponse_vide_du_modele_declenche_le_repli(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "cle-de-test")
    monkeypatch.setattr(httpx.Client, "post", lambda self, url, **kw: httpx.Response(
        200, json={"content": []}, request=httpx.Request("POST", url)))
    assert summary.resumer(FICHE, score_risque(FICHE, TODAY))[1] == "modele-de-phrases"


# ------------------------------------------------------------------ API

def test_post_triage(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    r = client.post("/triage", json=FICHE, headers={"X-Correlation-Id": "cid-42"})
    assert r.status_code == 200
    corps = r.json()
    assert set(corps) >= {"riskScore", "riskLevel", "summary", "source", "facteurs"}
    assert corps["riskLevel"] in ("FAIBLE", "MOYENNE", "ELEVEE", "CRITIQUE")
    assert r.headers["X-Correlation-Id"] == "cid-42"


def test_triage_accepte_une_fiche_minimale(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert client.post("/triage", json={}).status_code == 200


def test_correlation_id_genere_si_absent():
    assert client.get("/health").headers["X-Correlation-Id"]


def test_health_et_metriques():
    assert client.get("/health").json() == {"status": "UP"}
    assert client.get("/actuator/health").status_code == 200
    assert "triage_requests_total" in client.get("/metrics").text
