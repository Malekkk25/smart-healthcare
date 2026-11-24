# Conventions communes (A et B)

Ce fichier est le contrat « transverse » du projet. Toute modification doit être relue par l'autre personne avant d'être fusionnée dans `main`.

## 1. Identifiants

| Objet | Format | Exemple |
|---|---|---|
| Patient | `PAT-` + 6 chiffres | `PAT-000123` |
| Alerte | UUID v4 | `7b0f6c6e-3c1d-4a53-9a8e-0e6c1f1d2b44` |
| Rendez-vous | `APT-AAAA-NNNNNN` | `APT-2026-000451` |
| Créneau | `SLOT-AAAAMMJJ-HHMM-<médecin>` | `SLOT-20261125-0900-DOC-001` |
| Envoi d'ambulance | `DSP-AAAA-NNNNNN` | `DSP-2026-000087` |
| Médecin | `DOC-NNN` | `DOC-001` |
| Ambulance | `AMB-NN` | `AMB-03` |
| Corrélation | UUID v4 | `3f6c1d5e-8a47-4b8e-9c0a-2d7f4a1b9e63` |

## 2. En-tête `X-Correlation-Id`

- Le client (navigateur, simulateur, orchestrateur) peut l'envoyer. S'il est absent, **la première brique qui reçoit la requête le génère** (en pratique la Gateway).
- Chaque service **le reprend dans ses logs** (champ MDC `correlationId`), **le renvoie dans la réponse** et **le propage** à tous ses appels sortants (REST, SOAP, GraphQL, métadonnées gRPC `x-correlation-id`).

## 3. Authentification et rôles (JWT)

- Les JWT sont émis et vérifiés **uniquement par la Gateway** (HS256, secret `JWT_SECRET` du fichier `.env`).
- Connexion : `POST /api/auth/login` avec `{"username": "...", "password": "..."}` → `{"token", "tokenType": "Bearer", "expiresIn", "role", "patientId"}`.
- Champs du jeton :

| Champ | Contenu |
|---|---|
| `sub` | nom d'utilisateur |
| `role` | `PATIENT`, `MEDECIN` ou `ADMIN` |
| `patientId` | présent seulement pour `PATIENT` (ex. `PAT-000004`) |
| `iat`, `exp` | émission et expiration (8 h) |

- La Gateway transmet aux services internes les en-têtes `X-User-Id`, `X-User-Role` et `X-Patient-Id` (jamais fournis par le client : la Gateway les écrase).
- Les services internes **ne vérifient pas le JWT**. Ils ne sont joignables sans jeton que depuis le réseau Docker (appels service à service : orchestrateur → REST, GraphQL → REST, etc.). Seule la Gateway est l'entrée publique.
- Comptes de démonstration (mot de passe commun : variable `DEMO_PASSWORD` du `.env`) :

| Utilisateur | Rôle | patientId |
|---|---|---|
| `patient1` | PATIENT | `PAT-000002` |
| `patient2` | PATIENT | `PAT-000004` |
| `medecin` | MEDECIN | |
| `admin` | ADMIN | |

## 4. Matrice d'accès à la Gateway

| Route (préfixe `/api`) | PATIENT | MEDECIN | ADMIN |
|---|---|---|---|
| `POST /auth/login` | public | public | public |
| `/portal/slots` (GET) | oui | oui | oui |
| `/portal/appointments/**` | oui (les siens) | oui | oui |
| `/portal/patients/{id}` et `/prescriptions` | oui (le sien) | oui | oui (PUT inclus) |
| `GET /portal/patients` (liste) | non | oui | oui |
| `/portal/ambulances`, `/portal/emergency/**` | non | oui | oui |
| `/ehr/**` (SOAP, relais) | oui | oui | oui |
| `/dashboard/**` (GraphQL, B) | non | oui | oui |
| `/orchestrator/**` (workflows, B) | non | oui | oui |

La Gateway ne route que du HTTP et du WebSocket. Le navigateur ne parle jamais gRPC.

## 5. Format d'erreur JSON (REST, Gateway)

```json
{ "code": "RESSOURCE_INTROUVABLE", "message": "Aucun patient avec l'identifiant PAT-999999.", "correlationId": "3f6c1d5e-..." }
```

Codes utilisés : `REQUETE_INVALIDE` (400), `NON_AUTHENTIFIE` (401), `ACCES_REFUSE` (403), `RESSOURCE_INTROUVABLE` (404), `CRENEAU_INDISPONIBLE` (409), `AUCUNE_RESSOURCE_DISPONIBLE` (409), `SERVICE_INDISPONIBLE` (503/504), `TROP_DE_REQUETES` (429, en-tête `Retry-After`), `ERREUR_INTERNE` (500). Les erreurs du service SOAP sont des SOAP Faults (`PatientInconnuFault`, `DroitsExpiresFault`).

## 6. Jeu de données de test

- Source unique : `contracts/seed/patients.json` (20 patients, `PAT-000001` à `PAT-000020`). Régénéré par `generate_patients.py`, converti en SQL par `generate_sql.py`.
- Les mêmes 20 identifiants existent dans `ehr_db`, `portal_db` et doivent exister dans le simulateur de capteurs et `telemetry_db`.
- **Cas d'erreur volontaires côté SOAP** (pour tester le mode dégradé de l'orchestrateur) :
  - `PAT-000010` et `PAT-000020` : droits CNAM **expirés** (fin au 30/06/2026) → `DroitsExpiresFault`.
  - Tout identifiant absent de la base (ex. `PAT-000099`) → `PatientInconnuFault`.
- Ambulances de démonstration : `AMB-01`, `AMB-02`, `AMB-03` disponibles, `AMB-04` hors service, `AMB-05` en intervention.

## 7. Ports et bases

| Composant | Port | Base |
|---|---|---|
| web-client | 3000 | |
| gateway | 8080 | |
| soap-ehr | 8081 | `ehr_db` |
| rest-portal | 8082 | `portal_db` |
| graphql-dashboard | 8083 | |
| orchestrator | 8085 | `workflow_db` |
| ai-triage | 8086 | |
| grpc-telemetry | 9090 | `telemetry_db` |

Entre conteneurs, on utilise le nom du service Docker (ex. `http://rest-portal:8082`), jamais `localhost`.

## 8. Git

- Branche `main` protégée, une branche par fonctionnalité (`feat/...`), messages de commit en français au format `type: description`.
- Toute modification de `contracts/` doit être relue par l'autre personne.
- `docker compose up` doit toujours démarrer l'ensemble : chacun ajoute ses services au même `docker-compose.yml`.

## Conventions gRPC (ajout B)

- Métadonnée `x-correlation-id` sur chaque appel gRPC ; si absente, le serveur en génère un.
  Elle est placée dans le MDC des logs sous la clé `correlationId`.
- gRPC en clair (HTTP/2 sans TLS) à l'intérieur du réseau Docker, port 9090.
- Statuts d'erreur : `INVALID_ARGUMENT` (mesure invalide), `NOT_FOUND` (patient sans mesure),
  `UNAVAILABLE` (service en démarrage).
- Deux vocabulaires distincts : gravité d'alerte `FAIBLE|MOYENNE|ELEVEE|CRITIQUE`
  et niveau de risque patient `FAIBLE|MOYEN|ELEVE`. Ne jamais les mélanger.