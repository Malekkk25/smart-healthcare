# Rapport pour la personne B : ce qui est fait, ce qu'il reste à faire

Date : 22 novembre 2026 · Auteur : personne A · Dépôt : `smart-healthcare`

Ce document est écrit pour que B puisse démarrer **sans poser de question à A**. Il dit :
1. ce que A a livré et comment le faire tourner (sections 1 à 3) ;
2. comment appeler les services de A depuis ceux de B (section 4) ;
3. **ce que B doit faire, bloc par bloc, avec les critères de réussite** (sections 5 à 11) ;
4. ce que A attend de B pour finir ses propres pages, et les points à valider ensemble (sections 12 à 14).

Le plan de référence est « Smart Healthcare SOC : plan de travail en binôme ». Les ports, bases et technologies proposés y sont repris.

---

## 1. Résumé en dix lignes

- La **Partie 1 (A) est livrée dans son périmètre « cœur »** : socle (dépôt, docker-compose, jeu de 20 patients), contrats `ehr.wsdl` et `portal.openapi.yaml`, service **SOAP** (8081), service **REST** (8082), **Gateway** (8080, JWT, rôles, correlation-id, CORS, limitation de débit, audit), **client web** (3000).
- Tout démarre avec un seul `docker compose up -d --build`. Un script de test de bout en bout (`tools/smoke-test-a.sh`) vérifie la sécurité, le REST, le SOAP et les urgences à travers la Gateway.
- **B n'est bloquée sur rien de technique** : les services de A sont réels (pas de stubs), à jour et testés. B peut brancher directement son GraphQL et son orchestrateur sur le vrai REST et le vrai SOAP.
- Les trois contrats de B n'existent pas encore : `telemetry.proto`, `dashboard.graphql`, `workflow-api.md`. **Ils doivent être écrits et validés à deux en premier** (section 5). Des propositions complètes sont fournies : B peut les copier et les corriger.
- Côté client web, trois pages attendent B : **fiche patient enrichie** (GraphQL), **alertes en direct** (subscription) et **interventions** (API de l'orchestrateur). Elles sont déjà créées, routées et protégées par rôle, avec un bandeau « en attente ».
- `contracts/conventions.md` était **vide** : A l'a rédigé. B doit le relire et le corriger avant qu'il devienne définitif.
- A a ajouté **deux routes** au contrat REST (liste de patients filtrable, liste de rendez-vous d'un patient) et rendu `/emergency/dispatch` **idempotent**. Ces changements doivent être relus par B (section 13).

**Ce qui a été vérifié le 22/11/2026** : `tools/smoke-test-a.sh` passe à **57 sur 57** (authentification, rôles, REST, SOAP via la Gateway, urgences, erreurs au format commun, limitation de débit vérifiée à part). Le client web a été essayé dans un navigateur : connexion, rendez-vous, droits CNAM (réponse SOAP et SOAP Fault « droits expirés »), liste des patients. Une erreur CORS réelle, invisible avec `curl`, y a été trouvée et corrigée.

**Ce qui n'est PAS fait côté A (pour être transparent)** :

| Élément | Statut |
|---|---|
| WS-Security (UsernameToken) sur le SOAP | non fait (prévu S5 ; le plan le classe aussi dans les innovations « cœur » : à trancher) |
| Format inspiré de FHIR, lien Jitsi à chaque rendez-vous (bonus) | non fait |
| Observabilité : métriques Prometheus et tableau Grafana (bonus) | non fait (le correlation-id, lui, est en place) |
| Tests unitaires JUnit dans les services de A | non écrits : la couverture actuelle est le test de bout en bout |
| Pages admin « créneaux » et « ambulances » | lecture seule (plus un formulaire de déclenchement d'urgence pour tester) ; le contrat REST n'a pas de création de créneaux |
| Pages qui dépendent de B | fiche patient enrichie, alertes en direct, interventions, carte d'ambulance |

Le script de test crée des données à chaque exécution (rendez-vous, feuilles de soins, envois d'urgence). Pour repartir d'une base propre : `docker compose down -v && docker compose up -d`.

---

## 2. Démarrer l'environnement

Prérequis : Docker Desktop, `git`, `bash`, `curl`, `jq`, Python 3.

```bash
git clone <depot> && cd smart-healthcare
cp .env.example .env
# Éditer .env : POSTGRES_PASSWORD, JWT_SECRET (>= 32 caractères), DEMO_PASSWORD
docker compose up -d --build        # 1re fois : plusieurs minutes (compilation Maven dans Docker)
bash tools/smoke-test-a.sh          # doit finir par « 0 échecs »
```

| Quoi | Adresse |
|---|---|
| Client web | <http://localhost:3000> |
| Gateway (seule entrée publique) | <http://localhost:8080> |
| REST (accès direct, pour déboguer) | <http://localhost:8082> · Swagger : `/swagger-ui.html` |
| SOAP (accès direct) | <http://localhost:8081/ws/ehr?wsdl> |
| PostgreSQL | `localhost:5432` (bases `ehr_db`, `portal_db`, `telemetry_db`, `workflow_db`) |

Comptes de démonstration (mot de passe = `DEMO_PASSWORD` de votre `.env`) :

| Compte | Rôle | Patient associé |
|---|---|---|
| `patient1` | PATIENT | `PAT-000002` |
| `patient2` | PATIENT | `PAT-000004` |
| `medecin` | MEDECIN | |
| `admin` | ADMIN | |

Important : les bases `telemetry_db` et `workflow_db` existent déjà et sont **vides**. Chaque service crée ses tables lui-même au démarrage (comme A avec `schema.sql`).

---

## 3. Ce que A a livré

### 3.1 Contrats (dans `contracts/`)

| Fichier | Contenu | Statut |
|---|---|---|
| `ehr.wsdl`, `ehr.xsd`, `ehr/examples/*.xml` | Service SOAP 1.1 document/literal, namespace `http://smarthealthcare.tn/ehr`, 4 opérations, 2 SOAP Faults | livré, validé `xmllint` |
| `portal.openapi.yaml` | 13 routes, en-tête `X-Correlation-Id`, Bearer JWT, erreur commune | livré, validé Spectral (0 erreur) |
| `conventions.md` | Identifiants, JWT, matrice d'accès, format d'erreur, jeu de données, ports | **rédigé par A, à relire par B** |
| `seed/patients.json` | 20 patients `PAT-000001` à `PAT-000020` | livré |

### 3.2 Services

| Service | Techno | Port | Base | Détails |
|---|---|---|---|---|
| `soap-ehr` | Apache CXF 4 (code généré à partir du WSDL), Spring Boot 3.3 | 8081 | `ehr_db` | `/ws/ehr` |
| `rest-portal` | Spring Boot 3.3, JDBC, springdoc | 8082 | `portal_db` | Swagger UI |
| `gateway` | Spring Cloud Gateway | 8080 | aucune | `services/gateway/README.md` |
| `web-client` | React 18, Vite, nginx | 3000 | aucune | pages patient, médecin, admin |

### 3.3 Cas de test intégrés aux données (utiles pour le mode dégradé et la démo)

| Cas | Comportement |
|---|---|
| `PAT-000010`, `PAT-000020` | **Droits CNAM expirés** : le SOAP répond par `DroitsExpiresFault` (expirés le 2026-06-30) |
| `PAT-000099` (inexistant) | `PatientInconnuFault` côté SOAP, 404 côté REST |
| Acte `XX` (hors nomenclature) | `verifierDroitsCNAM` répond `droitsOuverts=false` (ce n'est pas un fault) |
| `PAT-000002`, `PAT-000004` | ont un historique d'actes certifiés, des ordonnances et des prises en charge comparables |
| Ambulances | `AMB-01`, `AMB-02`, `AMB-03` disponibles ; `AMB-04` hors service ; `AMB-05` en intervention |
| Créneaux | générés au démarrage pour 14 jours, 3 médecins `DOC-001..003` ; 2 créneaux d'urgence par jour et par médecin (17:00 et 17:30 UTC) |

---

## 4. Appeler les services de A depuis ceux de B

**Règle** : entre conteneurs, on appelle les services **directement**, avec leur nom Docker, **sans jeton** (la Gateway ne sert qu'au navigateur). Toujours propager `X-Correlation-Id`.

| Depuis B vers | URL interne |
|---|---|
| REST | `http://rest-portal:8082` |
| SOAP | `http://soap-ehr:8081/ws/ehr` |

### 4.1 REST : routes utiles à B

| Besoin | Appel | Remarque |
|---|---|---|
| Profil d'un patient | `GET /patients/{id}` | 404 si inconnu |
| **Liste filtrable de patients** | `GET /patients?service=&pathologie=&niveauRisque=` | pour les résolveurs GraphQL (filtres) |
| Ordonnances (= « traitement ») | `GET /patients/{id}/prescriptions` | |
| Ambulances | `GET /ambulances` | statut + position |
| **Envoi d'urgence** | `POST /emergency/dispatch` | idempotent sur `alertId` |
| Créneaux d'urgence libres (repli) | `GET /slots?doctorId=&date=` puis filtrer `urgence=true` | |
| Rendez-vous d'urgence manuel (repli) | `POST /appointments` | |

Exemple d'envoi d'urgence :

```bash
curl -s -X POST http://localhost:8082/emergency/dispatch \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"patientId":"PAT-000004","alertId":"7b0f6c6e-3c1d-4a53-9a8e-0e6c1f1d2b44","severity":"CRITIQUE",
       "location":{"latitude":36.8065,"longitude":10.1815,"adresse":"12 rue de Marseille, Tunis"}}'
# 201 {"dispatchId":"DSP-2026-000001","ambulanceId":"AMB-03","etaMinutes":1}
```

Comportement de `/emergency/dispatch` (à connaître pour l'étape 3 du workflow) :

1. **Idempotent** : même `alertId` ⇒ même réponse, aucun nouvel envoi. L'orchestrateur peut réessayer sans risque.
2. Choisit l'**ambulance disponible la plus proche** (distance à vol d'oiseau, 40 km/h) ; `etaMinutes ≥ 1`. L'ambulance devient `EN_INTERVENTION` puis se libère automatiquement après `ETA + 10 min`.
3. **Aucune ambulance disponible** ⇒ réserve un **créneau d'urgence** et crée un rendez-vous : réponse avec `ambulanceId: null`, `etaMinutes: null` et `fallbackAppointmentId`.
4. Ni ambulance ni créneau ⇒ `409 AUCUNE_RESSOURCE_DISPONIBLE`. Patient inconnu ⇒ `404`. Corps invalide ⇒ `400`.
5. `severity` : `FAIBLE`, `MOYENNE`, `ELEVEE`, `CRITIQUE`.

Format d'erreur REST (toujours) : `{ "code": "...", "message": "...", "correlationId": "..." }`.

### 4.2 SOAP : appel depuis Java

Le plus simple : **générer le client à partir du même WSDL** avec `cxf-codegen-plugin` (voir `services/soap-ehr/pom.xml`, 15 lignes à copier), ou appeler en XML brut avec `WebClient` et les fichiers `contracts/ehr/examples/*.request.xml` comme modèles.

```bash
curl -s -X POST http://localhost:8081/ws/ehr -H 'Content-Type: text/xml; charset=utf-8' \
  -H 'SOAPAction: http://smarthealthcare.tn/ehr/verifierDroitsCNAM' \
  --data-binary @contracts/ehr/examples/verifierDroitsCNAM.request.xml
```

Règles d'interprétation (**importantes pour le mode dégradé**) :

| Réponse | Nature | À faire dans l'orchestrateur |
|---|---|---|
| HTTP 200, `droitsOuverts=true` | succès | étape 2 réussie |
| HTTP 200, `droitsOuverts=false` | résultat métier | étape 2 réussie, noter « acte non pris en charge » |
| HTTP 500 + Fault `DroitsExpiresFault` | **résultat métier**, pas une panne | **continuer** : l'assurance ne bloque jamais les secours ; marquer « droits à régulariser » |
| HTTP 500 + Fault `PatientInconnuFault` | résultat métier | continuer, marquer « patient inconnu de l'assurance » |
| Timeout, connexion refusée, 502/503/504, circuit ouvert | **panne** | **mode dégradé** : continuer, planifier la vérification après coup |

Ne confondez donc pas « SOAP Fault » et « SOAP en panne ».

### 4.3 Les services de B vus par la Gateway

La Gateway route déjà vers B (rien à configurer côté A) :

| Chemin public | Cible interne | Rôles |
|---|---|---|
| `/api/dashboard/graphql` (HTTP) | `graphql-dashboard:8083/graphql` | MEDECIN, ADMIN |
| `/api/dashboard/graphql` (WebSocket, abonnements) | `graphql-dashboard:8083/graphql` | MEDECIN, ADMIN |
| `/api/orchestrator/**` | `orchestrator:8085/**` | MEDECIN, ADMIN |

Exigences sur vos services :

- Noms Docker **exactement** `graphql-dashboard`, `orchestrator`, `grpc-telemetry`, `ai-triage`, et ports du plan.
- GraphQL sur le chemin `/graphql`, abonnements en **WebSocket sur le même chemin** (protocole `graphql-transport-ws`, celui de Spring for GraphQL et du paquet `graphql-ws`).
- Flux d'étapes de l'orchestrateur en **SSE** (`text/event-stream`), sans mise en tampon.
- `GET /actuator/health` sur chaque service (utile pour `docker compose` et le diagnostic).
- Vos services **ne vérifient pas le JWT**. S'ils ont besoin de l'identité : en-têtes `X-User-Id`, `X-User-Role`, `X-Patient-Id`, posés par la Gateway.
- Les navigateurs envoient le jeton WebSocket dans `?access_token=<jwt>` ; ignorez ce paramètre côté B.

---

## 5. Première étape pour B : écrire et valider les trois contrats

Fin de S1 : « contrats validés à deux, stubs lancés ». Voici des propositions prêtes à adapter. Créez les fichiers dans `contracts/`, ouvrez une branche `feat/contrats-b`, et faites relire par A.

### 5.1 `contracts/telemetry.proto` (proposition)

```proto
syntax = "proto3";
package smarthealthcare.telemetry.v1;
option java_multiple_files = true;
option java_package = "tn.smarthealthcare.telemetry.grpc";
import "google/protobuf/timestamp.proto";

service Telemetry {
  // Le capteur (simulateur) envoie un flux de mesures ; le serveur répond à la fin du flux.
  rpc StreamVitals(stream VitalSample) returns (StreamSummary);
  // Les abonnés (orchestrateur, GraphQL) reçoivent les alertes au fil de l'eau.
  rpc SubscribeAlerts(SubscribeRequest) returns (stream Alert);
  // Lectures ponctuelles pour GraphQL et l'orchestrateur.
  rpc GetLatestVitals(PatientRef) returns (VitalSample);
  rpc ListVitals(ListVitalsRequest) returns (VitalList);
  rpc ListAlerts(ListAlertsRequest) returns (AlertList);
}

enum Consciousness { CONSCIOUSNESS_UNSPECIFIED = 0; ALERT = 1; CONFUSION = 2; VOICE = 3; PAIN = 4; UNRESPONSIVE = 5; }
enum Severity      { SEVERITY_UNSPECIFIED = 0; FAIBLE = 1; MOYENNE = 2; ELEVEE = 3; CRITIQUE = 4; } // mêmes mots que le REST
enum AlertStatus   { STATUS_UNSPECIFIED = 0; OPEN = 1; ACKNOWLEDGED = 2; CLOSED = 3; }

message VitalSample {
  string patient_id = 1;                         // PAT-000123
  google.protobuf.Timestamp measured_at = 2;
  int32  heart_rate = 3;                         // battements/min
  int32  resp_rate = 4;                          // respirations/min
  double spo2 = 5;                               // %
  int32  systolic_bp = 6;                        // mmHg
  int32  diastolic_bp = 7;
  double temperature = 8;                        // °C
  bool   on_oxygen = 9;
  Consciousness consciousness = 10;
  bool   fall_detected = 11;
}
message StreamSummary { int64 accepted = 1; int64 rejected = 2; }
message PatientRef { string patient_id = 1; }
message SubscribeRequest { repeated string patient_ids = 1; Severity min_severity = 2; }
message ListVitalsRequest { string patient_id = 1; int32 limit = 2; }
message VitalList { repeated VitalSample samples = 1; }
message ListAlertsRequest { string patient_id = 1; AlertStatus status = 2; int32 limit = 3; }
message AlertList { repeated Alert alerts = 1; }

message Alert {
  string alert_id = 1;                           // UUID
  string patient_id = 2;
  google.protobuf.Timestamp raised_at = 3;
  Severity severity = 4;
  int32 news2_score = 5;
  string reason = 6;                             // ex. "NEWS2 = 8 (SpO2 90 %, FR 27)"
  bool trend_worsening = 7;
  AlertStatus status = 8;
  VitalSample vitals = 9;                        // dernières constantes ayant déclenché l'alerte
}
```

Rappel du plan : `telemetry.proto` reste **interne à B** (simulateur, orchestrateur, GraphQL). Le navigateur et la Gateway ne parlent jamais gRPC.

### 5.2 `contracts/dashboard.graphql` (proposition)

```graphql
enum RiskLevel { FAIBLE MOYEN ELEVE }
enum Severity { FAIBLE MOYENNE ELEVEE CRITIQUE }
enum AlertStatus { OPEN ACKNOWLEDGED CLOSED }

input PatientFilter { service: String, pathologie: String, niveauRisque: RiskLevel }

type Query {
  patients(filtre: PatientFilter): [Patient!]!
  patient(id: ID!): Patient
  alerts(patientId: ID, status: AlertStatus, limit: Int = 50): [Alert!]!
}

type Subscription {
  alertCreated(minSeverity: Severity = ELEVEE): Alert!
}

type Patient {
  id: ID!
  nom: String!
  prenom: String!
  sexe: String!
  dateNaissance: String!
  service: String
  pathologie: String
  niveauRisque: RiskLevel
  numeroAssure: String
  tauxRemboursement: Int
  latestVital: Vital            # gRPC GetLatestVitals
  vitals(limit: Int = 20): [Vital!]!   # gRPC ListVitals
  alerts(status: AlertStatus): [Alert!]!  # gRPC ListAlerts
  treatment: Treatment          # REST /patients/{id}/prescriptions
}

type Vital {
  measuredAt: String!
  heartRate: Int
  respRate: Int
  spo2: Float
  systolicBp: Int
  diastolicBp: Int
  temperature: Float
  onOxygen: Boolean
  consciousness: String
}

type Alert {
  id: ID!
  patientId: ID!
  raisedAt: String!
  severity: Severity!
  news2Score: Int!
  reason: String
  trendWorsening: Boolean!
  status: AlertStatus!
  patient: Patient
}

type Treatment { ordonnances: [Prescription!]! }
type Prescription { id: ID!, doctorId: String, date: String, medicaments: [Medicament!]! }
type Medicament { nom: String!, posologie: String!, dureeJours: Int }
```

Ce que le client web de A attend de ce contrat : **une seule requête** pour la fiche patient (`patient(id)` avec `latestVital`, `alerts`, `treatment`), la requête `patients(filtre)` pour la liste, et la subscription `alertCreated` pour le flux en direct.

### 5.3 `contracts/workflow-api.md` (proposition)

| Route | Rôle | Réponse |
|---|---|---|
| `GET /workflows?status=&patientId=&limit=` | liste des interventions | tableau de `Workflow` |
| `GET /workflows/{id}` | détail | `Workflow` |
| `GET /workflows/{id}/events` | **SSE** : un événement à chaque changement d'étape | `text/event-stream` |
| `GET /workflows/events` | SSE de toutes les interventions (page admin) | `text/event-stream` |
| `POST /workflows` (facultatif) | déclenchement manuel pour les tests | `202` |

```json
{
  "id": "e3b0c442-98fc-4c14-9afb-f4c8996fb924",
  "alertId": "7b0f6c6e-3c1d-4a53-9a8e-0e6c1f1d2b44",
  "patientId": "PAT-000004",
  "correlationId": "3f6c1d5e-8a47-4b8e-9c0a-2d7f4a1b9e63",
  "status": "COMPLETED",              // RUNNING | COMPLETED | DEGRADED | FAILED
  "startedAt": "2026-11-25T09:00:00Z",
  "endedAt":   "2026-11-25T09:00:04Z",
  "steps": [
    { "name": "ALERTE",       "status": "SUCCEEDED", "attempts": 1, "detail": "NEWS2 = 8" },
    { "name": "DROITS",       "status": "DEGRADED",  "attempts": 3, "detail": "SOAP indisponible : vérification différée" },
    { "name": "PRISE_EN_CHARGE", "status": "SUCCEEDED", "attempts": 1, "detail": "AMB-03, ETA 7 min" },
    { "name": "FICHE",        "status": "SUCCEEDED", "attempts": 1, "detail": "fiche envoyée au médecin" }
  ]
}
```

Statuts d'étape : `PENDING`, `RUNNING`, `SUCCEEDED`, `DEGRADED` (repli utilisé), `FAILED`. Erreurs au format commun `{code, message, correlationId}`. La page admin de A lira `GET /workflows` et s'abonnera au SSE.

### 5.4 Relire `conventions.md`

A l'a rédigé à partir du plan. Vérifiez surtout : la matrice d'accès (section 4), les codes d'erreur, la durée du JWT (8 h), et ajoutez les conventions propres à gRPC (métadonnée `x-correlation-id`).

---

## 6. Service gRPC de télémétrie (port 9090, base `telemetry_db`)

**Techno proposée** : Spring Boot + grpc-java (starter `net.devh:grpc-server-spring-boot-starter` ou serveur grpc-java simple). Le port 9090 est du gRPC en clair (HTTP/2 sans TLS) à l'intérieur du réseau Docker.

### 6.1 Tâches

1. **`StreamVitals`** (flux client → serveur) : pour chaque mesure reçue, valider (valeurs plausibles, `patient_id` au format `PAT-000123`), calculer le score, enregistrer, évaluer les alertes. Réponse finale `StreamSummary`.
2. **`SubscribeAlerts`** (flux serveur → abonnés) : garder la liste des abonnés (filtre par patient et par gravité minimale) et leur pousser chaque nouvelle alerte. Prévoir la déconnexion propre d'un abonné.
3. **Seuils critiques et score NEWS2 calculés à la volée** (section 6.2).
4. **Stockage des dernières constantes par patient** : table `vital_sample` + une vue/colonne « dernière mesure » pour `GetLatestVitals`.
5. **Tendance** : repérer une dégradation progressive (section 6.3).
6. **Dédoublonnage** : tant qu'une alerte est `OPEN` pour un patient, ne pas en créer une autre de même niveau ; en créer une nouvelle seulement si le niveau monte. Identifiant d'alerte = **UUID** (convention du projet).
7. Propager `X-Correlation-Id` : les métadonnées gRPC `x-correlation-id` entrent dans le MDC des logs.
8. Schéma SQL créé au démarrage (`schema.sql` ou Flyway).

### 6.2 Score NEWS2 (tableau à implémenter)

NEWS2 (National Early Warning Score 2) additionne 7 paramètres, chacun noté de 0 à 3 (le « supplément d'oxygène » vaut 0 ou 2) :

| Points | 3 | 2 | 1 | 0 | 1 | 2 | 3 |
|---|---|---|---|---|---|---|---|
| Fréquence respiratoire (/min) | ≤ 8 | | 9-11 | 12-20 | | 21-24 | ≥ 25 |
| SpO2 échelle 1 (%) | ≤ 91 | 92-93 | 94-95 | ≥ 96 | | | |
| Oxygène supplémentaire | | oui = 2 | | non = 0 | | | |
| Pression systolique (mmHg) | ≤ 90 | 91-100 | 101-110 | 111-219 | | | ≥ 220 |
| Pouls (/min) | ≤ 40 | | 41-50 | 51-90 | 91-110 | 111-130 | ≥ 131 |
| Conscience | | | | Alert | | | Confusion, Voice, Pain, Unresponsive = 3 |
| Température (°C) | ≤ 35,0 | | 35,1-36,0 | 36,1-38,0 | 38,1-39,0 | ≥ 39,1 | |

Interprétation, à traduire en gravité (mêmes mots que le REST) :

| Condition | Gravité de l'alerte |
|---|---|
| Score total ≥ 7 | `CRITIQUE` |
| Score total 5 ou 6 | `ELEVEE` |
| Un seul paramètre à 3 (score total ≤ 4) | `MOYENNE` |
| Score total 1 à 4 | pas d'alerte (surveillance) ou `FAIBLE` |
| Chute détectée (`fall_detected`) | `ELEVEE` au minimum |
| Seuil absolu dépassé (SpO2 < 90 %, pouls > 140 ou < 40, systolique < 85) | `CRITIQUE`, même si le total NEWS2 est plus bas |

Une alerte `CRITIQUE` (ou « seuil dépassé ») est ce qui **déclenche le workflow** de l'orchestrateur (étape 1 du schéma).

Écrire des **tests unitaires** de la fonction de score (une ligne par ligne du tableau + les cas limites 8/9, 20/21, 91/92, 220).

### 6.3 Tendance (innovation « NEWS2 et tendance »)

Garder pour chaque patient les N dernières mesures (par ex. 10). Si le score NEWS2 a **augmenté d'au moins 2 points** sur les 5 dernières mesures **sans** avoir atteint le seuil critique, créer une alerte `MOYENNE` avec `trend_worsening = true` et un `reason` du type « dégradation progressive : NEWS2 3 → 6 en 4 min ». C'est ce qui rend le scénario « détresse respiratoire » démontrable.

### 6.4 Tables suggérées (`telemetry_db`)

```sql
CREATE TABLE vital_sample (id BIGSERIAL PRIMARY KEY, patient_id VARCHAR(20) NOT NULL, measured_at TIMESTAMPTZ NOT NULL,
  heart_rate INT, resp_rate INT, spo2 NUMERIC(4,1), systolic_bp INT, diastolic_bp INT, temperature NUMERIC(3,1),
  on_oxygen BOOLEAN, consciousness VARCHAR(20), fall_detected BOOLEAN, news2 INT);
CREATE INDEX ON vital_sample (patient_id, measured_at DESC);
CREATE TABLE alert (id UUID PRIMARY KEY, patient_id VARCHAR(20) NOT NULL, raised_at TIMESTAMPTZ NOT NULL,
  severity VARCHAR(10) NOT NULL, news2_score INT, reason TEXT, trend_worsening BOOLEAN, status VARCHAR(15) NOT NULL);
```

### 6.5 Critères de réussite

- Le simulateur envoie le scénario « crise cardiaque » : une alerte `CRITIQUE` apparaît en moins de 2 secondes chez un abonné `SubscribeAlerts`.
- Deux mesures critiques consécutives ne produisent **qu'une** alerte.
- `GetLatestVitals` renvoie la dernière mesure ; tests unitaires du score au vert.
- Logs avec `correlationId`.

---

## 7. Simulateur de capteurs (`tools/sensor-simulator`, Python)

**Rôle** : générer des mesures réalistes, en parallèle pour plusieurs patients, et les envoyer au service gRPC.

### 7.1 Tâches

1. Générer les stubs Python à partir de `contracts/telemetry.proto` (`grpcio-tools`).
2. Lire les patients dans `contracts/seed/patients.json` (**mêmes 20 identifiants** que partout).
3. Scénarios sélectionnables par patient (`--scenario patient:scenario`) :

| Scénario | Trajectoire des constantes (exemple) | Alerte attendue |
|---|---|---|
| `normal` | pouls 60-85, FR 12-18, SpO2 96-99, systolique 110-130, 36,5-37 °C | aucune |
| `crise_cardiaque` | pouls 80 → 150 puis > 40 (arythmie), systolique 120 → 85, SpO2 97 → 90, conscience Alert → Voice | `CRITIQUE` en quelques mesures |
| `detresse_respiratoire` | FR 16 → 28 progressivement, SpO2 96 → 88, oxygène supplémentaire activé, pouls 90 → 125 | `MOYENNE` (tendance) puis `CRITIQUE` |
| `chute` | mesures normales puis `fall_detected = true`, pouls en hausse brutale, conscience Confusion | `ELEVEE` |

4. Options : `--patients PAT-000004,PAT-000002`, `--speed 10` (accélération du temps), `--interval 1.0` (secondes entre deux mesures), `--duration 120`, `--seed 42` (reproductible), `--host localhost:9090`.
5. Plusieurs patients en parallèle (threads ou `asyncio`), avec du bruit aléatoire autour de la trajectoire.
6. **README de démo** : commandes exactes pour lancer chaque scénario.
7. Dockerfile facultatif (`python:3.11-slim`) et service `sensor-simulator` en profil docker compose `demo`.

### 7.2 Critères de réussite

Le scénario `crise_cardiaque` pour `PAT-000004` déclenche toujours la même séquence d'alertes (graine fixe) : c'est la base de la démo de 5 minutes.

---

## 8. Service GraphQL (port 8083, `graphql-dashboard`)

**Techno proposée** : Spring for GraphQL, client REST (`WebClient`) vers `rest-portal`, client gRPC vers `grpc-telemetry`. Aucune base : agrégation à la demande.

### 8.1 Tâches

1. Implémenter `dashboard.graphql` (section 5.2). Interface **GraphiQL** activée (`spring.graphql.graphiql.enabled=true`).
2. **Résolveurs** :
   - `patients(filtre)` → `GET http://rest-portal:8082/patients?service=&pathologie=&niveauRisque=` ;
   - `patient(id)` → `GET /patients/{id}` ;
   - `Patient.treatment` → `GET /patients/{id}/prescriptions` ;
   - `Patient.latestVital`, `Patient.vitals`, `Patient.alerts`, `alerts` → appels gRPC (`GetLatestVitals`, `ListVitals`, `ListAlerts`).
3. **Éviter le problème N+1** : sur `patients(...)`, charger les constantes et alertes en lot (`@BatchMapping` ou `DataLoader`).
4. **Subscription `alertCreated`** : s'abonner à `SubscribeAlerts` (gRPC) et republier chaque alerte via un `Flux`/`Sinks.Many` (multicast). Filtrer par `minSeverity`.
5. Propager `X-Correlation-Id` vers REST et gRPC (intercepteur `WebClient` et métadonnées gRPC).
6. **Résilience** : si le REST ou le gRPC est en panne, renvoyer des **erreurs GraphQL partielles** (`errors[]` + données disponibles) plutôt qu'un échec global. C'est ce qui sera coupé pendant le test de la démo.
7. Écrire quelques tests de requêtes avec un `HttpGraphQlTester`.

### 8.2 Critères de réussite

- Une requête unique ramène profil, constantes, alertes et traitement d'un patient (voir exemple ci-dessous).
- Les filtres `service`, `pathologie`, `niveauRisque` se combinent.
- Avec le REST arrêté, la requête renvoie quand même les constantes et alertes, avec une erreur ciblée sur `treatment`.
- Via la Gateway (`http://localhost:8080/api/dashboard/graphql`, jeton `medecin`), la requête et la subscription fonctionnent.

```graphql
query Fiche($id: ID!) {
  patient(id: $id) {
    nom prenom service pathologie niveauRisque
    latestVital { measuredAt heartRate spo2 respRate systolicBp }
    alerts(status: OPEN) { id severity news2Score reason }
    treatment { ordonnances { id date medicaments { nom posologie } } }
  }
}
```

---

## 9. Orchestrateur des urgences (port 8085, base `workflow_db`)

**Techno proposée** : Spring Boot + Resilience4j (retry, circuit breaker, timeout). C'est le **seul composant qui appelle les quatre services**.

### 9.1 Le workflow (schéma page 8 du plan)

Déclenchement : une alerte arrive par `SubscribeAlerts` avec une gravité `CRITIQUE` (ou un « seuil dépassé »).

| # | Étape | Appels | **Repli** si problème |
|---|---|---|---|
| 1 | **Alerte gRPC** : score NEWS2 critique ou seuil dépassé | abonnement `SubscribeAlerts` | **Idempotence** : si une intervention existe déjà pour cet `alertId`, on ne relance pas le workflow |
| 2 | **Droits, dossier** | SOAP `verifierDroitsCNAM`, `consulterHistorique` | **Mode dégradé** : SOAP indisponible ⇒ on continue, droits vérifiés après coup |
| 3 | **Prise en charge** | REST `POST /emergency/dispatch` (ambulance, sinon créneau d'urgence) | **3 essais**, puis créneau d'urgence **et alerte admin** |
| 4 | **Fiche enrichie** | GraphQL (+ résumé IA) poussée au médecin | **Fiche différée** : GraphQL en panne ⇒ fiche mise en file et **rejouée** |

Règle de fond : **l'assurance ne bloque jamais l'envoi des secours**. Chaque étape écrit son statut dans `workflow_db` **et** le publie en SSE (ce qui alimente la page admin des interventions de A).

### 9.2 Tâches

1. **Machine à états** persistée : table `workflow` (une ligne par intervention, **clé unique sur `alert_id`** ⇒ idempotence réelle, y compris après redémarrage) et table `workflow_step` (étape, statut, tentatives, détail, horodatages).
2. **Un `X-Correlation-Id` par intervention**, propagé à tous les appels sortants et enregistré dans `workflow.correlation_id` (c'est le fil d'Ariane de la démo).
3. **Étape 2** : appel SOAP avec timeout court (2 s) + circuit breaker. Interprétation des réponses : voir le tableau de la section 4.2 (Fault métier ≠ panne). En cas de panne : statut `DEGRADED`, tâche planifiée qui **rejoue** la vérification (`@Scheduled`) et complète le dossier quand le SOAP revient.
4. **Étape 3** : `POST /emergency/dispatch` avec le même `alertId` à chaque essai (idempotent). Après 3 échecs : réserver un créneau d'urgence (`GET /slots` filtré sur `urgence=true`, puis `POST /appointments`), puis **créer une alerte admin** (champ dans `workflow` + événement SSE `ADMIN_ALERT`). Si REST est complètement arrêté, le repli est impossible : statut `FAILED` + alerte admin.
5. **Étape 4** : appel GraphQL (`patient(id)`) pour assembler la fiche, + `POST http://ai-triage:8086/triage` (résumé IA, facultatif : texte de repli si le service IA est indisponible). Si GraphQL est en panne : **mettre la fiche en file** (table `pending_dossier`) et la rejouer périodiquement.
6. **Saga et compensation** : définir ce qui s'annule si une étape échoue après une étape réussie. Compensations possibles aujourd'hui : annuler le rendez-vous d'urgence (`DELETE /appointments/{id}`). A **ne propose pas encore** d'annulation d'envoi d'ambulance (elle se libère automatiquement après `ETA + 10 min`) : si vous en avez besoin, demandez `DELETE /emergency/dispatch/{id}` à A (une demi-heure de travail).
7. **API** : `GET /workflows`, `GET /workflows/{id}`, SSE (section 5.3).
8. **Journal d'exécution** de chaque intervention (une ligne par étape et par tentative).
9. Logs avec `correlationId` et `alertId`.

### 9.3 Test de résilience obligatoire pour la démo

Un script (par ex. `tools/chaos-demo.sh`) qui, **pendant qu'une alerte est en cours**, arrête tour à tour (`docker compose stop`) puis redémarre :

| Service coupé | Résultat attendu |
|---|---|
| `soap-ehr` | workflow `DEGRADED`, étape 2 en mode dégradé, étapes 3 et 4 réussies ; vérification rejouée au retour du SOAP |
| `rest-portal` | 3 essais, repli, alerte admin, statut final visible dans la page admin |
| `graphql-dashboard` | fiche différée, puis rejouée au redémarrage |

### 9.4 Critères de réussite

- Une même alerte envoyée deux fois ne crée **qu'une** intervention.
- Les trois coupures ci-dessus produisent le comportement attendu, **sans intervention manuelle**.
- Chaque étape apparaît en SSE dans l'ordre, avec son statut.

---

## 10. Service IA de triage (port 8086, `ai-triage`)

**Techno proposée** : Python, FastAPI. Aucune base.

1. `POST /triage` : entrée = profil + dernières constantes + score NEWS2 + traitement ; sortie = `{ riskScore (0-100), riskLevel, summary, source }`.
2. `riskScore` : règle simple et **déterministe** (pondération du NEWS2, de l'âge, de la pathologie, du niveau de risque du dossier). C'est la base qui marche toujours.
3. `summary` (bonus) : un LLM rédige une synthèse lisible pour le médecin à partir des données consolidées. **Repli obligatoire** : si le modèle est indisponible ou lent (> 5 s), renvoyer un texte généré par modèle de phrase (`source: "template"`), jamais une erreur.
4. `GET /health`. Pas de clé d'API dans le dépôt : variable d'environnement dans `.env` (et `.env.example` sans valeur).
5. Dockerfile et service dans `docker-compose.yml`.

Critère : l'orchestrateur obtient toujours un résumé en moins de 6 s, avec ou sans modèle.

---

## 11. Ajouter vos services à `docker-compose.yml`

Les services de A sont déjà dedans. Ajoutez les vôtres **dans le même fichier** (un `docker compose up` doit tout démarrer). Un gabarit est en commentaire à la fin du fichier. Points d'attention :

```yaml
  grpc-telemetry:
    build: { context: ., dockerfile: services/grpc-telemetry/Dockerfile }
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/telemetry_db
      DB_USER: ${POSTGRES_USER:-postgres}
      DB_PASSWORD: ${POSTGRES_PASSWORD}
    ports: ["9090:9090"]
    depends_on: { postgres: { condition: service_healthy } }
```

- Les bases `telemetry_db` et `workflow_db` existent déjà.
- Copier le principe des Dockerfile de A (`services/rest-portal/Dockerfile`) : build Maven en plusieurs étapes avec cache (`--mount=type=cache,target=/root/.m2`), image finale `eclipse-temurin:17-jre`.
- Ne mettez pas de mot de passe en dur : tout passe par `.env`.
- Ne publiez pas de nouveau port inutile ; ceux du plan suffisent.

---

## 12. Ce que A attend de B (pour finir les pages du client web)

| Page du client web (A) | Bloquée par | Quand c'est livré, A fait |
|---|---|---|
| Fiche patient enrichie (constantes, alertes, traitement) | `dashboard.graphql` + service GraphQL | remplace le bandeau « en attente » par **une requête GraphQL** |
| Liste de patients filtrable | rien (utilise aujourd'hui `GET /patients` du REST) | peut basculer sur `patients(filtre)` si vous le souhaitez |
| Flux d'alertes en direct (médecin) | subscription `alertCreated` | branchement WebSocket avec `?access_token=` |
| Interventions (admin) | `workflow-api.md` + SSE | tableau + suivi des 4 étapes en direct |
| Carte d'ambulance (bonus) | position et ETA dans l'intervention | affichage sur carte |

Autrement dit : **dès que les trois contrats sont validés (fin S1) et que vos services répondent, A branche les pages en une demi-journée**. En attendant, B peut tester ses services avec GraphiQL, `grpcurl` et `curl` sans le client web.

Ce que A fait de son côté pendant ce temps (aucun blocage sur B) : bonus WS-Security sur le SOAP, format inspiré de FHIR et liens de visioconférence côté REST, observabilité (correlation-id de bout en bout, métriques), Dockerfiles et tests finaux.

---

## 13. Points à relire et à valider ensemble

Toute modification d'un contrat doit être relue par l'autre personne. Voici ce que A a changé ou décidé **sans pouvoir demander** à B :

| # | Sujet | Détail | Décision à prendre |
|---|---|---|---|
| 1 | `conventions.md` | était vide ; A l'a rédigé | B relit et corrige |
| 2 | Contrat REST : ajout `GET /patients` (filtres `service`, `pathologie`, `niveauRisque`) | indispensable aux filtres du GraphQL | accepter |
| 3 | Contrat REST : ajout `GET /appointments?patientId=` | indispensable à « mes rendez-vous » | accepter |
| 4 | `/emergency/dispatch` **idempotent** sur `alertId` | demandé par le schéma (reprises) | accepter |
| 5 | Identifiants de créneaux `SLOT-AAAAMMJJ-HHMM-DOC-001` | le médecin fait partie de l'identifiant | accepter |
| 6 | Vocabulaire de gravité : `FAIBLE`, `MOYENNE`, `ELEVEE`, `CRITIQUE` | différent du niveau de risque patient (`FAIBLE`, `MOYEN`, `ELEVE`) | confirmer ; **ne pas mélanger les deux** |
| 7 | Annulation d'une ambulance | pas d'endpoint ; libération automatique à `ETA + 10 min` | demander `DELETE /emergency/dispatch/{id}` si la saga en a besoin |
| 8 | Faults SOAP | le code SOAP envoyé par CXF est `Server` (les exemples du contrat disent `Client`) ; le nom du détail (`PatientInconnuFault`, `DroitsExpiresFault`) est le critère fiable | tester sur le nom du détail, pas sur `faultcode` |
| 9 | Sécurité des services internes | pas de JWT entre services (réseau Docker) ; seule la Gateway authentifie | accepter pour le projet |
| 10 | Page « ambulances et créneaux » | le plan la place dans les pages patient, A l'a mise côté **admin** (c'est de la gestion) | confirmer |

---

## 14. Planning conseillé pour B (d'après le plan, à recaler sur la date de rendu)

| Semaine | B | Synchro avec A |
|---|---|---|
| **S1** | `telemetry.proto`, `dashboard.graphql`, `workflow-api.md`, relecture de `conventions.md` ; squelette du simulateur ; Dockerfiles vides des services | **Contrats validés à deux** (fin S1) |
| **S2** | gRPC : flux, seuils, NEWS2 (+ tests unitaires) ; simulateur avec les 4 scénarios | Tout tourne dans `docker compose` |
| **S3** | GraphQL : requêtes, filtres, résolveurs vers le **vrai** REST ; subscription ; début de l'orchestrateur | Le GraphQL lit le vrai REST |
| **S4** | Orchestrateur : workflow nominal de bout en bout (alerte → droits → ambulance → fiche) | **Premier workflow de bout en bout** ; A branche ses pages |
| **S5** | Saga, mode dégradé, reprises, fiche différée ; service IA ; test de coupure des services | Tests croisés des scénarios d'échec |
| **S6** | Scénario de démo (5 min), SDL et `.proto` finalisés, tests, rapport | **Démo complète** et rapport |

Scénario de démo cible (5 minutes) : *alerte cardiaque d'un patient à domicile, prise en charge automatique, fiche affichée chez le médecin.* Concrètement : le simulateur lance `crise_cardiaque` sur `PAT-000004` ⇒ alerte `CRITIQUE` ⇒ l'orchestrateur vérifie les droits (SOAP), envoie une ambulance (REST) ⇒ la page « alertes en direct » du médecin s'affiche ⇒ la fiche enrichie s'ouvre ⇒ la page admin montre les 4 étapes.

### Règles Git (rappel)

- Une branche par fonctionnalité : `feat/grpc-telemetry`, `feat/simulateur`, `feat/graphql`, `feat/orchestrateur`, `feat/ia-triage`.
- Messages de commit en français, format `type: description` (`feat:`, `fix:`, `docs:`, `test:`).
- Tout changement dans `contracts/` est relu par A avant fusion.
- Ne jamais commiter `.env`, des clés d'API, `node_modules/`, `target/`.

### Liste de vérification finale de B

- [ ] `telemetry.proto`, `dashboard.graphql`, `workflow-api.md` validés par A et fusionnés
- [ ] gRPC : NEWS2 testé, alertes dédoublonnées, abonnement fonctionnel
- [ ] Simulateur : 4 scénarios reproductibles, README de démo
- [ ] GraphQL : fiche en une requête, filtres, subscription, erreurs partielles
- [ ] Orchestrateur : 4 étapes, 4 replis, idempotence, SSE, journal
- [ ] Test de coupure SOAP / REST / GraphQL passé
- [ ] Service IA avec repli sans modèle
- [ ] Tous les services dans `docker-compose.yml` ; `docker compose up` démarre tout
- [ ] Page « alertes en direct » et page « interventions » de A branchées et vérifiées
- [ ] Schéma d'architecture et diagramme du workflow (à faire à deux)

---

## Annexe : aide-mémoire des commandes

```bash
# Connexion et jeton (via la Gateway)
curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"medecin","password":"<DEMO_PASSWORD>"}' | jq -r .token

# Appel authentifié
curl -s http://localhost:8080/api/portal/patients?niveauRisque=ELEVE -H "Authorization: Bearer $TOKEN" | jq

# Journal d'audit de la Gateway
docker compose logs -f gateway | grep AUDIT

# Couper puis relancer un service (test de résilience)
docker compose stop soap-ehr && docker compose start soap-ehr

# Reconstruire un seul service
docker compose up -d --build rest-portal

# Remettre la base à zéro (efface les données !)
docker compose down -v
```
