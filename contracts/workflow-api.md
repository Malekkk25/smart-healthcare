# API de l'orchestrateur (port 8085)

Accès via la Gateway : `/api/orchestrator/**` (rôles MEDECIN, ADMIN).
Erreurs au format commun : `{ "code": "...", "message": "...", "correlationId": "..." }`.

## Routes

| Route | Rôle | Réponse |
|---|---|---|
| `GET /workflows?status=&patientId=&limit=` | liste des interventions | tableau de `Workflow` |
| `GET /workflows/{id}` | détail d'une intervention | `Workflow` (404 `WORKFLOW_INTROUVABLE`) |
| `GET /workflows/{id}/events` | SSE : un événement à chaque changement d'étape | `text/event-stream` |
| `GET /workflows/events` | SSE de toutes les interventions (page admin) | `text/event-stream` |
| `POST /workflows` | déclenchement manuel (tests) | `202` + `Workflow` |
| `GET /actuator/health` | santé | 200 |

Corps de `POST /workflows` : `{ "alertId": "<uuid>", "patientId": "PAT-000004" }`.
Si une intervention existe déjà pour cet `alertId` : `200` avec l'intervention existante.

## Workflow

```json
{
  "id": "e3b0c442-98fc-4c14-9afb-f4c8996fb924",
  "alertId": "7b0f6c6e-3c1d-4a53-9a8e-0e6c1f1d2b44",
  "patientId": "PAT-000004",
  "correlationId": "3f6c1d5e-8a47-4b8e-9c0a-2d7f4a1b9e63",
  "status": "DEGRADED",
  "startedAt": "2026-11-25T09:00:00Z",
  "endedAt": "2026-11-25T09:00:04Z",
  "adminAlert": null,
  "steps": [
    { "name": "ALERTE", "status": "SUCCEEDED", "attempts": 1, "detail": "NEWS2 = 8" },
    { "name": "DROITS", "status": "DEGRADED", "attempts": 3, "detail": "SOAP indisponible : vérification différée" },
    { "name": "PRISE_EN_CHARGE", "status": "SUCCEEDED", "attempts": 1, "detail": "AMB-03, ETA 7 min" },
    { "name": "FICHE", "status": "SUCCEEDED", "attempts": 1, "detail": "fiche envoyée au médecin" }
  ]
}
```

Statuts du workflow : `RUNNING`, `COMPLETED`, `DEGRADED`, `FAILED`.
Statuts d'étape : `PENDING`, `RUNNING`, `SUCCEEDED`, `DEGRADED` (repli utilisé), `FAILED`.
Noms d'étape : `ALERTE`, `DROITS`, `PRISE_EN_CHARGE`, `FICHE`.
`adminAlert` : `null` ou texte expliquant pourquoi l'administrateur doit intervenir.

## Flux SSE

Chaque événement a un `event:` et un `data:` JSON.

| `event` | `data` |
|---|---|
| `WORKFLOW_STARTED` | `Workflow` |
| `STEP_UPDATED` | `{ "workflowId": "...", "step": { name, status, attempts, detail } }` |
| `ADMIN_ALERT` | `{ "workflowId": "...", "message": "..." }` |
| `WORKFLOW_ENDED` | `Workflow` |

```
event: STEP_UPDATED
data: {"workflowId":"e3b0...","step":{"name":"DROITS","status":"SUCCEEDED","attempts":1,"detail":"droits ouverts"}}
```

Un événement `: keepalive` (commentaire SSE) est envoyé toutes les 15 s.