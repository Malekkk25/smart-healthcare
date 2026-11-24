# Stubs des services de B

Ces deux petits serveurs respectent `contracts/dashboard.graphql` et `contracts/workflow-api.md`. Ils permettent à A de développer et tester le client web **avant** la livraison des vrais services de B.

| Stub | Remplace | Port | Contenu |
|---|---|---|---|
| `graphql` | `graphql-dashboard` | 8083 | requêtes et filtres (profils et ordonnances **réels**, lus dans le REST), constantes vitales **simulées**, subscription `alertCreated` (une alerte simulée toutes les 30 s) |
| `orchestrator` | `orchestrator` | 8085 | `GET/POST /workflows`, SSE (`/workflows/events`, `/workflows/{id}/events`), une intervention simulée toutes les 45 s (avec mode dégradé et alerte admin) |

## Utilisation

```bash
docker compose --profile stubs up -d --build
```

Les stubs portent **les mêmes noms Docker** que les vrais services : quand B ajoutera `graphql-dashboard` et `orchestrator` à `docker-compose.yml`, il retirera simplement le profil `stubs` des deux entrées (ou supprimera les stubs). La Gateway n'a rien à changer.

Variables : `STUB_ALERT_INTERVAL` (s, 0 = désactivé), `STUB_WORKFLOW_INTERVAL` (s), `REST_URL`.

Déclencher une intervention à la main (mode dégradé avec `?degrade=true`) :

```bash
curl -X POST "http://localhost:8085/workflows?degrade=true" -H 'Content-Type: application/json' \
  -d '{"alertId":"7b0f6c6e-3c1d-4a53-9a8e-0e6c1f1d2b44","patientId":"PAT-000004"}'
```

> Ce ne sont **pas** des implémentations : pas de gRPC, pas de score NEWS2, pas de base de données.
