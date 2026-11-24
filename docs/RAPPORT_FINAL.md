# Smart Healthcare : bilan du projet

Projet SOC (Service Oriented Computing), réalisé en binôme. Plateforme de santé connectée : prise de rendez-vous et dossier
patient, surveillance à distance par capteurs, détection automatique d'une urgence, envoi d'une ambulance et fiche
d'intervention pour le médecin.

Date du bilan : 24 novembre 2026.

## 1. Objectif et périmètre

Le projet démontre une architecture **orientée services** où chaque technologie est utilisée pour ce qu'elle fait de mieux :

| Besoin | Technologie | Pourquoi |
|---|---|---|
| Échanges formels avec l'assurance (droits CNAM, feuilles de soins) | **SOAP** (WSDL, XSD, SOAP Faults) | contrat strict, erreurs métier typées |
| Portail patient (rendez-vous, ordonnances, ambulances) | **REST** (OpenAPI 3) | simple, cacheable, lisible par un navigateur |
| Tableau de bord du médecin | **GraphQL** (requêtes, filtres, subscription) | une seule requête pour une vue consolidée, alertes en direct |
| Mesures des capteurs et alertes | **gRPC** (flux dans les deux sens) | débit, flux continus, contrat Protobuf |
| Workflow d'urgence | **Orchestrateur** (saga, reprises, SSE) | le seul composant qui appelle les quatre services |

Voir [`ARCHITECTURE.md`](ARCHITECTURE.md) pour les schémas et [`DEMO.md`](DEMO.md) pour la démonstration.

## 2. Ce qui est livré

| Composant | Port | Contenu |
|---|---|---|
| Gateway | 8080 | JWT (patient, médecin, admin), matrice de rôles, accès limité aux données du patient, correlation-id, CORS, limitation de débit, journal d'audit, erreurs au format commun |
| SOAP `soap-ehr` | 8081 | 4 opérations (droits, feuille de soins, historique, comparaison de garanties), 2 SOAP Faults, code généré depuis le WSDL |
| REST `rest-portal` | 8082 | rendez-vous (CRUD), créneaux, patients, ordonnances, ambulances, envoi d'urgence idempotent, lien de visio Jitsi, vue FHIR en lecture seule |
| GraphQL `graphql-dashboard` | 8083 | `patients(filtre)`, `patient(id)`, `alerts`, subscription `alertCreated` ; erreurs partielles ciblées |
| gRPC `grpc-telemetry` | 9090 | `StreamVitals`, `SubscribeAlerts`, lectures ; score **NEWS2**, seuils absolus, tendance, dédoublonnage, fermeture automatique |
| Orchestrateur | 8085 | workflow en 4 étapes avec replis, disjoncteurs, rejeu différé, compensation, journal, SSE |
| IA `ai-triage` | 8086 | score de risque déterministe + résumé (modèle de langage optionnel, repli sur un modèle de phrases) |
| Simulateur de capteurs | | 4 scénarios reproductibles (normal, crise cardiaque, détresse respiratoire, chute) |
| Client web | 3000 | pages patient, médecin et administrateur ; alertes et interventions en direct |
| Observabilité | 3001, 9091 | Prometheus et Grafana (profil `observability`) |

## 3. Innovations

| Innovation | Statut | Où |
|---|---|---|
| Score NEWS2 et tendance (dégradation progressive) | fait | `grpc-telemetry` : `News2.java`, `AlertPolicy.java` |
| Simulateur de capteurs à scénarios | fait | `tools/sensor-simulator` |
| Temps réel jusqu'au navigateur (subscription GraphQL, SSE) | fait | `Alertes.jsx`, `Interventions.jsx` |
| Orchestration résiliente (saga, reprises, mode dégradé) | fait | `WorkflowEngine.java` |
| Sécurité par rôles (JWT, journal d'audit) | fait | Gateway |
| WS-Security (UsernameToken) sur le SOAP | **non fait** | voir section 6 |
| REST inspiré de FHIR | fait (lecture seule) | `FhirController.java` |
| Téléconsultation (lien Jitsi) | fait | champ `visioUrl` des rendez-vous |
| Résumé IA de la fiche d'intervention | fait ; **modèle de langage non testé de bout en bout** (aucune clé d'API dans le dépôt) | `ai-triage` |
| Carte d'ambulance | **non fait** | |
| Observabilité (correlation-id, Prometheus, Grafana) | fait | tous les services |

## 4. Choix de conception à retenir

- **Contrats d'abord** : `contracts/` contient le WSDL, l'OpenAPI, le schéma GraphQL, le `.proto` et la description de l'API de l'orchestrateur. Le code est **généré** à partir du WSDL (SOAP) et du `.proto` (gRPC), et le schéma GraphQL servi est le fichier du contrat lui-même.
- **Un fault métier n'est pas une panne** : un SOAP Fault « droits expirés » est un résultat (l'étape réussit, la facturation sera régularisée) ; seule une indisponibilité déclenche le mode dégradé.
- **L'assurance ne bloque jamais les secours.**
- **Idempotence partout où l'on réessaie** : une alerte n'ouvre qu'une intervention (clé unique en base) ; l'envoi d'ambulance est idempotent sur l'identifiant d'alerte ; une alerte ouverte n'est dupliquée que si sa gravité monte.
- **Le repli est toujours testé** : service IA absent, GraphQL en panne, REST en panne, SOAP en panne.
- **Une seule source d'alertes** : le service gRPC. L'orchestrateur et le GraphQL s'y abonnent et se reconnectent seuls.
- **Défense en profondeur** : la Gateway authentifie et filtre ; le service REST vérifie en plus qu'un patient n'accède qu'à ses données.

## 5. Tests

| Niveau | Quoi | Résultat |
|---|---|---|
| Unitaires Java | SOAP (11), REST (16), Gateway (16), gRPC (29), GraphQL (10), orchestrateur (34) | 116 tests |
| Unitaires Python | service IA (20), scénarios du simulateur (10) | 30 tests |
| Bout en bout, partie A | `tools/smoke-test-a.sh` : sécurité, rôles, REST, FHIR, SOAP, urgences, erreurs | 67 vérifications |
| Bout en bout, chaîne d'urgence | `tools/smoke-test-b.sh` : capteurs → alerte → 4 étapes → fiche chez le médecin, idempotence, SSE, métriques | 31 vérifications |
| Résilience | `tools/chaos-demo.sh` : coupe réellement le SOAP, le GraphQL, le service IA puis le REST pendant une intervention | 20 vérifications |
| Contrat | `portal.openapi.yaml` validé par Spectral ; WSDL et exemples validés par `xmllint` | 0 erreur |

Ces tests ont trouvé des défauts réels, corrigés :

- CORS de la Gateway absent sur la route de connexion (invisible avec `curl`, trouvé dans le navigateur) ;
- code HTTP 500 au lieu de 503 quand un service est arrêté (nom Docker non résolu) ;
- **client HTTP de l'orchestrateur refusé par le service IA** (en-tête `Upgrade: h2c` refusé par uvicorn) : le service IA n'était jamais utilisé et le repli le masquait ; test de non-régression ajouté, qui échoue sans la correction ;
- **cache DNS de la Gateway** : après la recréation d'un conteneur (nouvelle IP), la Gateway répondait 503 pendant 10 minutes ; corrigé dans la Gateway, le GraphQL et l'orchestrateur (cache DNS de 5 s) ; vérifié sur la Gateway en forçant un changement d'adresse (de .12 à .13), pas séparément sur les deux autres services.

## 6. Limites connues et travaux possibles

| Limite | Détail |
|---|---|
| WS-Security | non implémenté : changement incompatible pour l'orchestrateur et le navigateur (un secret ne peut pas être embarqué dans le client web). Solution envisagée : compte de service dont le mot de passe vient de `.env`, ajouté par l'orchestrateur et par la Gateway. |
| Carte d'ambulance | non faite (la position de l'ambulance et le délai d'arrivée sont disponibles dans l'intervention). |
| Modèle de langage | le résumé par un LLM est codé (clé `ANTHROPIC_API_KEY`) et testé avec un faux serveur, mais **pas avec le vrai service**. Sans clé, le modèle de phrases est utilisé. Activer le LLM envoie la fiche du patient à un service externe : à réserver aux données de test. |
| Domicile du patient | le jeu de données n'a pas d'adresse : le domicile utilisé pour choisir l'ambulance est **simulé** (stable par patient, Grand Tunis). |
| Ambulance non rappelable | pas d'endpoint d'annulation d'envoi : la compensation annule le rendez-vous d'urgence ; l'ambulance se libère seule à ETA + 10 min. |
| Sécurité des services internes | pas de JWT entre services (réseau Docker), gRPC et HTTP en clair, mot de passe de démonstration commun. Acceptable pour un projet pédagogique, pas pour la production. |
| Métriques publiques | `/actuator/prometheus` de la Gateway est lisible sans jeton. |
| Pas d'intégration continue | les tests se lancent à la main (commandes dans le `README`). |
| Montée en charge | non mesurée. La limitation de débit de la Gateway est en mémoire (une seule instance). |

## 7. Répartition du travail et relecture par B

| Partie | Responsable | Livrables |
|---|---|---|
| Socle, SOAP, REST, Gateway, client web (partie A) | personne A | `ehr.wsdl`, `portal.openapi.yaml`, services, Gateway, client, bonus FHIR / Jitsi / observabilité |
| Contrats de la partie B | personne B (Nour) | `telemetry.proto`, `dashboard.graphql`, `workflow-api.md` (fusionnés dans `master`) |
| Services de la partie B | écrits à la demande de A sur la branche `feat/partie-b-complete`, d'après les contrats de Nour | gRPC, GraphQL, orchestrateur, IA, simulateur |

**Relecture par B (à faire avant fusion dans `master`)** : la partie B a été implémentée sans que Nour la voie. Points à lui faire valider :

1. `contracts/workflow-api.md` : j'y ai **ajouté** des champs facultatifs (`dossier`, `ambulanceId`, `etaMinutes`, `appointmentId`, `severity`, `news2Score`, `reason`) et deux routes (`/journal`, `/cancel`). Rien n'a été retiré ni renommé.
2. Seuils de gravité NEWS2 (`AlertPolicy.java`) : total ≥ 7 critique, 5-6 élevé, un seul paramètre à 3 moyen, seuils absolus (SpO2 < 90 %, pouls > 140 ou < 40, PAS < 85), chute = au moins élevé, tendance = hausse d'au moins 2 points sur 5 mesures.
3. Déclenchement du workflow : alertes de gravité `CRITIQUE` uniquement (paramètre `orchestrator.trigger-severity`).
4. Le fichier `docs/RAPPORT_PERSONNE_B.md` décrit le cahier des charges suivi.

## 8. Lancer et vérifier

```bash
cp .env.example .env                 # renseigner POSTGRES_PASSWORD, JWT_SECRET, DEMO_PASSWORD
docker compose up -d --build         # plateforme complète
bash tools/smoke-test-a.sh           # 67 vérifications
bash tools/smoke-test-b.sh           # 31 vérifications
bash tools/chaos-demo.sh             # 20 vérifications, coupe réellement des services
bash tools/reset-demo.sh             # remet la démo à zéro
```
