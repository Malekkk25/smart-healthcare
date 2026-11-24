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

## Ajouts compatibles (implémentation de l'orchestrateur)

Ces ajouts ne changent rien aux champs et routes ci-dessus ; un client qui les ignore continue de fonctionner.

**Champs facultatifs de `Workflow`** : `severity`, `news2Score`, `reason` (l'alerte d'origine), `dispatchId`,
`ambulanceId`, `etaMinutes`, `appointmentId` (résultat de l'étape 3 ; `appointmentId` = créneau d'urgence de repli) et
`dossier` (la fiche d'intervention, `null` tant qu'elle n'est pas prête) :

```json
"dossier": {
  "alerte":       { "alertId": "...", "severity": "CRITIQUE", "news2Score": 9, "reason": "..." },
  "intervention": { "dispatchId": "DSP-2026-000087", "ambulanceId": "AMB-03", "etaMinutes": 7, "appointmentId": null, "adminAlert": null },
  "patient":      { "id": "PAT-000004", "nom": "Bouzid", "latestVital": { }, "alerts": [ ], "treatment": { } },
  "triage":       { "riskScore": 92, "riskLevel": "CRITIQUE", "summary": "...", "source": "llm | modele-de-phrases | template-orchestrateur" }
}
```

**Routes supplémentaires**

| Route | Rôle |
|---|---|
| `GET /workflows/{id}/journal` | journal d'exécution : une ligne par changement d'état d'étape (`step`, `status`, `attempt`, `detail`, `at`) |
| `POST /workflows/{id}/cancel` | compensation (saga) : annule le rendez-vous d'urgence éventuel, abandonne les tâches différées, lève une alerte admin. Une ambulance déjà envoyée ne peut pas être rappelée (libération automatique à ETA + 10 min) |

**Comportement**
- Tâches différées : si le SOAP est en panne (étape 2) ou le GraphQL en panne (étape 4), l'étape passe en `DEGRADED`, puis
  est **rejouée automatiquement** (toutes les 15 s, 40 essais maximum). À la réussite, l'étape passe en `SUCCEEDED`
  (événement `STEP_UPDATED`) et le statut global est recalculé (événement `WORKFLOW_ENDED`).
- Un SOAP Fault `DroitsExpiresFault` ou `PatientInconnuFault` est un **résultat** : l'étape 2 réussit avec le détail
  « facturation à régulariser » et l'envoi des secours n'est jamais bloqué.
- Le déclenchement automatique a lieu pour toute alerte gRPC de gravité `CRITIQUE` ; une alerte déjà connue (`alertId`) ne relance rien.