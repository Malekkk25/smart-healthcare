# Smart Healthcare

Projet SOC (Service Oriented Computing) réalisé en binôme : une plateforme de santé connectée reposant sur une architecture orientée services (SOAP, REST, GraphQL, gRPC, orchestration BPEL-like et triage par IA).

## Services

| Service | Rôle | Port |
|---|---|---|
| `gateway` | Passerelle d'entrée (API Gateway) | 8080 |
| `soap-ehr` | Dossier médical électronique (SOAP) | 8081 |
| `rest-portal` | Portail patient (REST) | 8082 |
| `graphql-dashboard` | Tableau de bord (GraphQL) | 8083 |
| `grpc-telemetry` | Télémétrie des capteurs (gRPC) | 9090 |
| `orchestrator` | Orchestration des workflows | 8085 |
| `ai-triage` | Triage assisté par IA | 8086 |
| `web-client` | Interface web | 3000 |

## Structure

```
contracts/                    Contrats (WSDL, OpenAPI, GraphQL, proto)
infra/postgres/               Scripts d'initialisation PostgreSQL
services/                     Les services (un dossier par service)
tools/sensor-simulator/       Simulateur de capteurs
web-client/                   Client web
```

## Démarrage

```bash
cp .env.example .env   # puis modifier le mot de passe
docker compose up
```

Pour l'instant, seul PostgreSQL (port 5432) est défini ; il crée les bases `ehr_db`, `portal_db`, `telemetry_db` et `workflow_db`.
