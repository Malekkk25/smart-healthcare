# Architecture

Les schémas sont écrits en [Mermaid](https://mermaid.js.org/) : GitHub, VS Code et la plupart des visionneuses Markdown les affichent directement.

## 1. Vue d'ensemble : 4 services, 1 gateway, 1 orchestrateur

Deux parties reliées seulement par des contrats d'interface (dossier `contracts/`).

```mermaid
flowchart TB
    web["Client web React<br/>portail patient, tableau médecin, admin<br/>:3000"]
    gw["API Gateway<br/>JWT, rôles, correlation-id, CORS, limitation de débit<br/>:8080"]

    subgraph P1["Partie 1 : données et accès (A)"]
        soap["Dossier patient<br/>SOAP, WSDL, droits CNAM<br/>:8081 · ehr_db"]
        rest["Portail patient<br/>REST, OpenAPI, RDV, ordonnances, ambulances<br/>:8082 · portal_db"]
    end

    subgraph P2["Partie 2 : temps réel et intelligence (B)"]
        gql["Tableau médical<br/>GraphQL, SDL, vue consolidée<br/>:8083"]
        grpc["Télémétrie<br/>gRPC, .proto, NEWS2, alertes<br/>:9090 · telemetry_db"]
        orch["Orchestrateur des urgences<br/>saga, reprises, mode dégradé, SSE<br/>:8085 · workflow_db"]
        ai["Service IA de triage<br/>score de risque, résumé<br/>:8086"]
        sim["Capteurs virtuels<br/>script Python"]
    end

    web -->|HTTP, WebSocket, SSE| gw
    gw --> soap
    gw --> rest
    gw --> gql
    gw --> orch

    sim -->|StreamVitals| grpc
    grpc -->|SubscribeAlerts| orch
    grpc -->|SubscribeAlerts, constantes| gql
    gql -->|profil, ordonnances| rest

    orch -->|droits| soap
    orch -->|ambulance, créneau| rest
    orch -->|fiche| gql
    orch -->|résumé| ai
```

Règles d'architecture :

- Le navigateur ne parle **qu'à la Gateway** ; il ne parle jamais gRPC.
- L'**orchestrateur** est le seul composant qui appelle les quatre services.
- Le service **gRPC** est la seule source des alertes (pour l'orchestrateur et le GraphQL).
- Les services internes ne vérifient pas le JWT : seule la Gateway authentifie (réseau Docker).
- Le service **SOAP** exige en plus un en-tête **WS-Security UsernameToken** (mot de passe haché, nonce) : la Gateway et l'orchestrateur l'ajoutent, le navigateur ne détient aucun secret.

## 2. Workflow d'urgence : 4 étapes, 4 replis

```mermaid
flowchart LR
    A["1. Alerte gRPC<br/>NEWS2 critique<br/>ou seuil dépassé"] --> B["2. Droits, dossier<br/>SOAP verifierDroitsCNAM"]
    B --> C["3. Prise en charge<br/>REST : ambulance<br/>ou créneau urgent"]
    C --> D["4. Fiche enrichie<br/>GraphQL + résumé IA<br/>poussée au médecin"]

    A -. doublon .-> A2["Idempotence<br/>alerte déjà ouverte :<br/>on ne relance pas"]
    B -. échec .-> B2["Mode dégradé<br/>SOAP indisponible : on continue,<br/>droits vérifiés après coup"]
    C -. échec .-> C2["Reprises, repli<br/>3 essais, puis créneau urgent<br/>et alerte admin"]
    D -. échec .-> D2["Fiche différée<br/>GraphQL en panne :<br/>fiche mise en file et rejouée"]

    style A2 stroke-dasharray: 5 5
    style B2 stroke-dasharray: 5 5
    style C2 stroke-dasharray: 5 5
    style D2 stroke-dasharray: 5 5
```

> La couverture d'assurance ne bloque **jamais** l'envoi des secours : elle sert à la facturation, d'où le mode dégradé de l'étape 2.
> Un SOAP Fault métier (droits expirés, patient inconnu) est un **résultat**, pas une panne.

Chaque étape écrit son statut dans `workflow_db` et le publie en SSE (page « Interventions »). Statuts d'étape :
`PENDING` → `RUNNING` → `SUCCEEDED`, ou `DEGRADED` (repli utilisé), ou `FAILED`.

### Séquence nominale

```mermaid
sequenceDiagram
    autonumber
    participant S as Capteurs
    participant T as gRPC télémétrie
    participant O as Orchestrateur
    participant E as SOAP EHR
    participant R as REST portail
    participant G as GraphQL
    participant I as Service IA
    participant M as Médecin (navigateur)

    S->>T: StreamVitals (constantes)
    T->>T: score NEWS2, règles d'alerte, dédoublonnage
    T-->>O: SubscribeAlerts : alerte CRITIQUE
    T-->>G: SubscribeAlerts : alerte CRITIQUE
    G-->>M: subscription alertCreated (WebSocket)
    O->>O: crée l'intervention (clé unique = alertId)
    O->>E: verifierDroitsCNAM
    E-->>O: droits ouverts, 80 %
    O->>R: POST /emergency/dispatch (idempotent)
    R-->>O: AMB-03, ETA 7 min
    O->>G: query patient (profil, constantes, alertes, traitement)
    G->>R: profil, ordonnances
    G->>T: dernières constantes, alertes
    G-->>O: fiche
    O->>I: POST /triage
    I-->>O: score de risque, résumé
    O-->>M: SSE : STEP_UPDATED, WORKFLOW_ENDED
    M->>G: fiche patient (une requête)
```

### Comportement en cas de panne

| Service coupé pendant l'intervention | Étape touchée | Comportement | Au retour du service |
|---|---|---|---|
| SOAP | 2. Droits | 3 essais, disjoncteur, étape `DEGRADED` (« vérification différée »). Les secours partent. | rejeu automatique, étape `SUCCEEDED` (« vérifié après coup ») |
| REST | 3. Prise en charge | 3 essais, repli sur un créneau d'urgence, **alerte admin** ; si le REST est totalement arrêté : `FAILED` + alerte admin | la fiche en attente est rejouée |
| GraphQL | 4. Fiche | étape `DEGRADED` (« fiche mise en file ») | rejeu automatique de la fiche |
| Service IA | 4. Fiche | résumé généré localement (`source: template-orchestrateur`), étape `SUCCEEDED` | rien à faire |
| Télémétrie | 1. Alerte | les abonnés (orchestrateur, GraphQL) se reconnectent toutes les 3 s | reprise du flux |

## 3. Données

| Base | Service | Contenu |
|---|---|---|
| `ehr_db` | soap-ehr | patients (droits CNAM), nomenclature des actes, feuilles de soins, prises en charge |
| `portal_db` | rest-portal | patients, créneaux, rendez-vous, ordonnances, ambulances, envois d'urgence |
| `telemetry_db` | grpc-telemetry | constantes vitales (avec score NEWS2), alertes |
| `workflow_db` | orchestrator | interventions, étapes, journal d'exécution, tâches différées |

Les mêmes 20 patients (`PAT-000001` à `PAT-000020`, `contracts/seed/patients.json`) existent dans les bases et dans le simulateur.

## 4. Contrats

| Contrat | Fichier | Technologie |
|---|---|---|
| Dossier patient | `contracts/ehr/ehr.wsdl`, `ehr.xsd` | SOAP 1.1 document/literal |
| Portail patient | `contracts/portal.openapi.yaml` | REST, OpenAPI 3.0 |
| Tableau médical | `contracts/dashboard.graphql` | GraphQL (requêtes, filtres, subscription) |
| Télémétrie | `contracts/telemetry.proto` | gRPC (flux client et serveur) |
| Orchestrateur | `contracts/workflow-api.md` | REST + SSE |
| Conventions | `contracts/conventions.md` | identifiants, JWT, erreurs, ports |
