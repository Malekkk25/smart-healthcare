"""Service IA de triage (port 8086) : score de risque et résumé de la fiche d'intervention."""
from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

from .scoring import score_risque
from .summary import resumer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(correlation)s] %(message)s")


class _CorrelationFilter(logging.Filter):
    """Ajoute le correlation-id courant aux logs."""
    current = "-"

    def filter(self, record: logging.LogRecord) -> bool:
        record.correlation = _CorrelationFilter.current
        return True


for _h in logging.getLogger().handlers:
    _h.addFilter(_CorrelationFilter())

log = logging.getLogger("ai-triage")
app = FastAPI(title="Service IA de triage", version="1.0.0",
              description="Score de risque déterministe + résumé de fiche (modèle de langage avec repli).")

TRIAGES = Counter("triage_requests_total", "Demandes de triage", ["source"])
DUREE = Histogram("triage_duration_seconds", "Durée d'une demande de triage")


@app.middleware("http")
async def correlation(request: Request, call_next):
    """Reprend X-Correlation-Id (ou en génère un), le met dans les logs et le renvoie."""
    cid = request.headers.get("x-correlation-id") or str(uuid.uuid4())
    _CorrelationFilter.current = cid
    response = await call_next(request)
    response.headers["X-Correlation-Id"] = cid
    return response


@app.post("/triage")
def triage(fiche: dict[str, Any]):
    """Entrée : la fiche de l'orchestrateur {alerte, intervention, patient}.
    Sortie : {riskScore, riskLevel, summary, source, facteurs}. Ne renvoie jamais d'erreur à cause du modèle."""
    with DUREE.time():
        score = score_risque(fiche)
        texte, source = resumer(fiche, score)
    TRIAGES.labels(source).inc()
    log.info("triage : score %s (%s), résumé par %s", score["riskScore"], score["riskLevel"], source)
    return {"riskScore": score["riskScore"], "riskLevel": score["riskLevel"], "summary": texte,
            "source": source, "facteurs": score["facteurs"]}


@app.get("/health")
@app.get("/actuator/health")
def health():
    return {"status": "UP"}


@app.get("/metrics")
@app.get("/actuator/prometheus")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.exception_handler(Exception)
async def erreur(request: Request, exc: Exception):
    log.exception("erreur inattendue")
    return JSONResponse(status_code=500, content={"code": "ERREUR_INTERNE", "message": "Erreur interne du serveur.",
                                                  "correlationId": _CorrelationFilter.current})
