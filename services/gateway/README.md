# Gateway (port 8080)

Point d'entrée unique de la plateforme : Spring Cloud Gateway (WebFlux).
Le navigateur ne parle qu'à la Gateway. Elle ne route que du **HTTP et du WebSocket**.

## Rôle

1. **Authentification** : `POST /api/auth/login` émet un JWT (HS256) pour les comptes de démonstration.
2. **Autorisation** : valide le JWT, applique la matrice de rôles (`AccessRules.java`) et vérifie qu'un patient n'accède qu'à ses données.
3. **Identité transmise** aux services internes : `X-User-Id`, `X-User-Role`, `X-Patient-Id` (toujours écrasés côté Gateway, jamais pris du client).
4. **Correlation-id** : génère `X-Correlation-Id` s'il est absent, le transmet et le renvoie.
5. **CORS** : origine autorisée `http://localhost:3000` (variable `CORS_ORIGINS`), filtre global `CorsConfig.java` (couvre les routes et `/api/auth/login`).
5. **Limitation de débit** : par adresse IP et par minute, 600 requêtes pour l'API et 30 pour la connexion (`RATE_LIMIT_PER_MINUTE`, `RATE_LIMIT_LOGIN_PER_MINUTE`). Au-delà : `429 TROP_DE_REQUETES` avec `Retry-After`.
6. **Erreurs** : format commun `{code, message, correlationId}`, y compris service injoignable (503) ou lent (504).
7. **Audit** : chaque requête authentifiée est tracée par le logger `audit`.

## Routes (fichier `src/main/resources/application.yml`)

| Chemin public | Cible | Réécriture | Rôles |
|---|---|---|---|
| `POST /api/auth/login` | local (Gateway) | | public |
| `/api/portal/**` | `rest-portal:8082` | retire `/api/portal` | voir ci-dessous |
| `/api/ehr` , `/api/ehr/**` | `soap-ehr:8081` | `/api/ehr` → `/ws/ehr` (WSDL : `/api/ehr?wsdl`) | tout authentifié |
| `/api/dashboard/**` (WebSocket) | `graphql-dashboard:8083` (ws) | retire `/api/dashboard` | MEDECIN, ADMIN |
| `/api/dashboard/**` (HTTP) | `graphql-dashboard:8083` | `/api/dashboard/graphql` → `/graphql` | MEDECIN, ADMIN |
| `/api/orchestrator/**` | `orchestrator:8085` | retire `/api/orchestrator` | MEDECIN, ADMIN |
| `GET /actuator/health` | local | | public |

Les adresses des services de B se changent par variables d'environnement : `DASHBOARD_URL`, `DASHBOARD_WS_URL`, `ORCHESTRATOR_URL`.

### Matrice des rôles pour `/api/portal`

| Route | PATIENT | MEDECIN | ADMIN |
|---|---|---|---|
| `GET /slots` | oui | oui | oui |
| `GET /appointments?patientId=` | le sien | oui | oui |
| `POST/GET/PUT/DELETE /appointments...` | les siens (contrôlé par rest-portal) | oui | oui |
| `GET /patients` (liste) | non | oui | oui |
| `GET /patients/{id}` et `/prescriptions` | le sien | oui | oui |
| `PUT /patients/{id}` | le sien | non | oui |
| `GET /ambulances`, `POST /emergency/dispatch` | non | oui | oui |

## WebSocket (abonnements GraphQL)

Le navigateur ne peut pas envoyer d'en-tête `Authorization` sur un WebSocket : le jeton est accepté dans le paramètre
`?access_token=<jwt>` **uniquement pour les requêtes d'upgrade WebSocket**.

## Configuration

| Variable | Rôle |
|---|---|
| `JWT_SECRET` | secret de signature (32 octets minimum), fichier `.env` |
| `DEMO_PASSWORD` | mot de passe commun des comptes de démonstration, fichier `.env` |
| `CORS_ORIGINS` | origine du client web |

Comptes : `patient1` (PAT-000002), `patient2` (PAT-000004), `medecin`, `admin`.
