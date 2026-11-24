# Smart Healthcare

Projet SOC (Service Oriented Computing) réalisé en binôme : une plateforme de santé connectée reposant sur une architecture orientée services (SOAP, REST, GraphQL, gRPC, orchestration d'un workflow d'urgence et triage par IA).

## Services

| Service | Rôle | Port | Partie | État |
|---|---|---|---|---|
| `gateway` | Passerelle d'entrée : JWT, rôles, correlation-id, CORS, limitation de débit | 8080 | A | livré |
| `soap-ehr` | Dossier médical électronique et facturation CNAM (SOAP) | 8081 | A | livré |
| `rest-portal` | Portail patient : rendez-vous, ordonnances, ambulances, urgences (REST) | 8082 | A | livré |
| `graphql-dashboard` | Tableau de bord médecin (GraphQL, subscription) | 8083 | B | livré |
| `grpc-telemetry` | Télémétrie, score NEWS2 et alertes (gRPC) | 9090 | B | livré |
| `orchestrator` | Workflow d'urgence : saga, reprises, mode dégradé, SSE | 8085 | B | livré |
| `ai-triage` | Score de risque et résumé de la fiche (IA avec repli) | 8086 | B | livré |
| `web-client` | Interface web (React) | 3000 | A | livré |

Documents :

- [`docs/RAPPORT_FINAL.md`](docs/RAPPORT_FINAL.md) : bilan du projet (choix, innovations, tests, limites).
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) : architecture, workflow d'urgence et comportement en cas de panne (schémas).
- [`docs/DEMO.md`](docs/DEMO.md) : scénario de démonstration de 5 minutes.
- [`docs/RAPPORT_PERSONNE_B.md`](docs/RAPPORT_PERSONNE_B.md) : rapport de passation à la personne B (état avant l'implémentation de la partie 2).
- [`contracts/conventions.md`](contracts/conventions.md) : identifiants, JWT, erreurs, jeu de données, ports.
- [`contracts/`](contracts/) : `ehr.wsdl`, `portal.openapi.yaml` (A), `dashboard.graphql`, `workflow-api.md`, `telemetry.proto` (B).
- [`tools/sensor-simulator/`](tools/sensor-simulator/README.md) : simulateur de capteurs (4 scénarios reproductibles).
- [`tools/b-stubs/`](tools/b-stubs/README.md) : stubs du GraphQL et de l'orchestrateur, pour développer le client sans la chaîne complète (`docker-compose.stubs.yml`).

## Structure

```
contracts/                    Contrats d'interface (WSDL, OpenAPI, conventions, jeu de données)
docs/                         Rapports et documentation
infra/postgres/               Initialisation PostgreSQL (une base par service)
services/                     Un dossier par service
tools/                        Simulateur de capteurs, stubs, tests de bout en bout et de panne
web-client/                   Client web React
```

## Démarrage

```bash
cp .env.example .env     # puis renseigner POSTGRES_PASSWORD, JWT_SECRET et DEMO_PASSWORD
docker compose up -d --build
```

Le premier démarrage compile les services Java dans Docker (plusieurs minutes). Ensuite :

- Client web : <http://localhost:3000>
- Gateway : <http://localhost:8080>
- Swagger UI du portail REST : <http://localhost:8082/swagger-ui.html>
- WSDL du service SOAP : <http://localhost:8081/ws/ehr?wsdl>

Comptes de démonstration (mot de passe : `DEMO_PASSWORD` du fichier `.env`) : `patient1` (PAT-000002), `patient2` (PAT-000004), `medecin`, `admin`.

## Observabilité (profil `observability`)

```bash
docker compose --profile observability up -d     # Prometheus : http://localhost:9091 · Grafana : http://localhost:3001
```

Grafana (compte `admin`, mot de passe `GRAFANA_PASSWORD` du `.env`) charge automatiquement le tableau « Smart Healthcare - services » : disponibilité, requêtes par seconde, erreurs 5xx, latence p95, refus 4xx, mémoire JVM. Le `correlation-id` est dans les logs de chaque service.

## Tester

```bash
bash tools/smoke-test-a.sh     # sécurité, REST, FHIR, SOAP, urgences, à travers la Gateway
bash tools/smoke-test-b.sh     # chaîne d'urgence : capteurs -> alerte -> 4 étapes -> fiche chez le médecin
bash tools/chaos-demo.sh       # coupe le SOAP, le GraphQL, l'IA puis le REST pendant une intervention
```

Démonstration : `bash tools/reset-demo.sh` puis voir [`docs/DEMO.md`](docs/DEMO.md).

Tests unitaires (JUnit, dans Docker, sans installer Maven) :

```bash
docker run --rm -v "$PWD:/repo" -v sh-m2cache:/root/.m2 -w /repo/services/rest-portal maven:3.9-eclipse-temurin-17 mvn -B test
```

(remplacer `rest-portal` par `gateway` ou `soap-ehr`).
