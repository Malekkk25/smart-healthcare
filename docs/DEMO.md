# Scénario de démonstration (5 minutes)

**Histoire** : une patiente à domicile, Sarra Bouzid (`PAT-000004`, cardiologie, arythmie), fait une crise cardiaque. Ses
capteurs envoient des mesures ; la plateforme détecte l'urgence, envoie une ambulance et prépare la fiche, sans intervention
humaine. Le médecin voit tout apparaître en direct.

## Avant de commencer (une fois)

```bash
docker compose up -d --build                 # plateforme complète
bash tools/reset-demo.sh                     # alertes fermées, 3 ambulances disponibles
docker compose --profile observability up -d # facultatif : Grafana sur http://localhost:3001
```

Ouvrir deux fenêtres de navigateur sur <http://localhost:3000> :

| Fenêtre | Compte | Page à ouvrir |
|---|---|---|
| 1 : le médecin | `medecin` | **Alertes en direct** |
| 2 : l'administrateur | `admin` | **Interventions** |

(Le mot de passe est la variable `DEMO_PASSWORD` du fichier `.env`.) Un terminal est prêt à lancer le simulateur.

## Déroulé

| Temps | Action | Ce que l'on voit | Ce que l'on explique |
|---|---|---|---|
| 0:00 | Présenter la fenêtre « Alertes en direct » : « Flux : connecté, aucune alerte » | Le point vert de connexion | 4 services (SOAP, REST, GraphQL, gRPC), 1 gateway, 1 orchestrateur, reliés par des contrats |
| 0:30 | Dans le terminal : `docker compose --profile demo run --rm sensor-simulator --host grpc-telemetry:9090 --scenario PAT-000004:crise_cardiaque --speed 3 --duration 60` | Les mesures défilent : pouls en hausse, tension et SpO2 en baisse | Le simulateur est un script Python qui envoie un flux gRPC ; le score **NEWS2** est calculé à la volée côté serveur |
| 1:00 | Regarder la fenêtre du médecin | Une alerte **élevée** apparaît toute seule, puis une alerte **critique** (sans rafraîchir la page) | Subscription GraphQL par WebSocket ; le dédoublonnage évite d'envoyer 40 alertes pour une même crise |
| 1:30 | Basculer sur la fenêtre admin « Interventions » | Une intervention apparaît et ses **4 étapes** passent au vert en direct | L'orchestrateur : alerte → droits (SOAP) → prise en charge (REST) → fiche (GraphQL + IA), suivi en SSE |
| 2:30 | Fenêtre du médecin : cliquer sur la patiente dans l'alerte | La **fiche enrichie** : constantes, alertes ouvertes, ordonnances, dernière intervention avec le résumé et le niveau de risque | Une seule requête GraphQL assemble profil (REST), constantes et alertes (gRPC) ; le résumé vient du service IA, avec un repli sans modèle |
| 3:00 | Page « Carte » (médecin ou admin) | L'ambulance affectée se déplace vers le domicile de la patiente (temps accéléré), avec « arrivée dans N min » | Choix de l'ambulance la plus proche, délai estimé, libération automatique ; le domicile est simulé |
| 3:30 | **Panne** : `docker compose stop soap-ehr`, puis relancer le simulateur sur `PAT-000002:detresse_respiratoire` | L'étape « Droits » passe en **mode dégradé**, les secours partent quand même | « La couverture d'assurance ne bloque jamais l'envoi des secours » |
| 4:15 | `docker compose start soap-ehr` | Environ 20 secondes plus tard, l'étape « Droits » repasse en **réussie** (« vérifié après coup ») | Disjoncteur, essais, rejeu automatique |
| 4:45 | (facultatif) Grafana : tableau « Smart Healthcare - services » | Requêtes, erreurs 5xx, latence | Observabilité : correlation-id dans les logs et métriques Prometheus |
| 5:00 | Conclusion | | Les services de A et de B ne partagent que des contrats ; chaque panne est absorbée par un repli |

## Variantes

- **Chute** : `--scenario PAT-000012:chute` → alerte `ELEVEE` (ne déclenche pas d'intervention : seuil critique non atteint).
- **Détresse respiratoire** : `--scenario PAT-000002:detresse_respiratoire` → alerte de **tendance** (`MOYENNE`, dégradation progressive), puis `CRITIQUE`.
- **Plusieurs patients en parallèle** : répéter `--scenario` autant de fois que nécessaire.
- **Droits expirés** : `PAT-000010` et `PAT-000020` ; la fiche indique « facturation à régulariser » et l'ambulance part.
- **Plus d'ambulance** : au 4e envoi, le REST réserve un créneau d'urgence et l'administrateur reçoit une alerte.

## Test automatique du même scénario

```bash
bash tools/smoke-test-b.sh   # capteurs → alerte → 4 étapes → fiche chez le médecin (30 vérifications)
bash tools/chaos-demo.sh     # coupe SOAP, GraphQL, IA puis REST pendant une intervention
```
