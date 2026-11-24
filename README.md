# Smart Healthcare

Projet SOC (Service Oriented Computing) réalisé en binôme : une plateforme de santé connectée reposant sur une architecture orientée services (SOAP, REST, GraphQL, gRPC, orchestration d'un workflow d'urgence et triage par IA).

## Services

| Service | Rôle | Port | Partie | État |
|---|---|---|---|---|
| `gateway` | Passerelle d'entrée : JWT, rôles, correlation-id, CORS, limitation de débit | 8080 | A | livré |
| `soap-ehr` | Dossier médical électronique et facturation CNAM (SOAP) | 8081 | A | livré |
| `rest-portal` | Portail patient : rendez-vous, ordonnances, ambulances, urgences (REST) | 8082 | A | livré |
| `graphql-dashboard` | Tableau de bord médecin (GraphQL) | 8083 | B | à faire |
| `grpc-telemetry` | Télémétrie des capteurs et alertes (gRPC) | 9090 | B | à faire |
| `orchestrator` | Orchestration du workflow d'urgence | 8085 | B | à faire |
| `ai-triage` | Triage assisté par IA | 8086 | B | à faire |
| `web-client` | Interface web (React) | 3000 | A | livré (pages de B testées avec des stubs) |

Documents :

- [`docs/RAPPORT_PERSONNE_B.md`](docs/RAPPORT_PERSONNE_B.md) : ce que B doit faire, en détail.
- [`contracts/conventions.md`](contracts/conventions.md) : identifiants, JWT, erreurs, jeu de données, ports.
- [`contracts/`](contracts/) : `ehr.wsdl`, `portal.openapi.yaml` (A), `dashboard.graphql`, `workflow-api.md`, `telemetry.proto` (B).
- [`tools/b-stubs/`](tools/b-stubs/README.md) : stubs du GraphQL et de l'orchestrateur, pour développer sans attendre B (`docker compose --profile stubs up -d --build`).

## Structure

```
contracts/                    Contrats d'interface (WSDL, OpenAPI, conventions, jeu de données)
docs/                         Rapports et documentation
infra/postgres/               Initialisation PostgreSQL (une base par service)
services/                     Un dossier par service
tools/                        Simulateur de capteurs (B) et tests de bout en bout
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
bash tools/smoke-test-a.sh     # sécurité, REST, FHIR, SOAP, urgences et services de B, à travers la Gateway
```

Tests unitaires (JUnit, dans Docker, sans installer Maven) :

```bash
docker run --rm -v "$PWD:/repo" -v sh-m2cache:/root/.m2 -w /repo/services/rest-portal maven:3.9-eclipse-temurin-17 mvn -B test
```

(remplacer `rest-portal` par `gateway` ou `soap-ehr`).
