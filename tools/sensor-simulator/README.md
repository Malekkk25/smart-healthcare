# Simulateur de capteurs

Envoie des constantes vitales au service gRPC de télémétrie (`StreamVitals`), plusieurs patients en parallèle, avec des
scénarios **reproductibles** (même graine = mêmes mesures).

## Scénarios

| Scénario | Ce qui se passe | Alerte attendue |
|---|---|---|
| `normal` | constantes stables autour de valeurs propres à chaque patient | aucune |
| `crise_cardiaque` | tachycardie (pouls 72 → 148), hypotension (PAS 122 → 84), baisse de la SpO2 (97 → 90), puis confusion | `CRITIQUE` vers la 25e mesure, après une phase `ELEVEE` |
| `detresse_respiratoire` | fréquence respiratoire 16 → 29, SpO2 97 → 90, oxygène supplémentaire dès la 30e mesure | dégradation progressive (`MOYENNE`, tendance) puis `CRITIQUE` |
| `chute` | mesures normales puis chute détectée (mesures 11 et 12), pouls en hausse, confusion passagère | `ELEVEE` |

## Utilisation

Avec Docker (aucune installation) :

```bash
# crise cardiaque de PAT-000004, 5 fois plus vite que le temps réel
docker compose --profile demo run --rm sensor-simulator --host grpc-telemetry:9090 \
    --scenario PAT-000004:crise_cardiaque --speed 5

# plusieurs patients en parallèle
docker compose --profile demo run --rm sensor-simulator --host grpc-telemetry:9090 \
    --scenario PAT-000004:crise_cardiaque --scenario PAT-000002:detresse_respiratoire --scenario PAT-000012:chute --speed 10

# tous les patients du jeu de données, en scénario normal
docker compose --profile demo run --rm sensor-simulator --host grpc-telemetry:9090 --all --duration 30 --speed 20
```

Sans Docker (Python 3.11) :

```bash
pip install -r requirements.txt
sh gen_stubs.sh                         # génère les stubs gRPC à partir de contracts/telemetry.proto
python simulate.py --host localhost:9090 --scenario PAT-000004:crise_cardiaque --speed 5
```

## Options

| Option | Rôle | Défaut |
|---|---|---|
| `--host` | adresse du service gRPC | `localhost:9090` |
| `--scenario PATIENT:SCENARIO` | scénario d'un patient (répétable) | |
| `--patients A,B` / `--all` | patients stables / les 20 patients du jeu de données | |
| `--speed` | accélération du temps (5 = 5 fois plus vite) | 1 |
| `--interval` | secondes simulées entre deux mesures | 1.0 |
| `--duration` | nombre de mesures par patient | 70 |
| `--loop`, `--cycle` | rejoue le scénario en boucle (Ctrl+C pour arrêter) | non |
| `--seed` | graine : même graine = mêmes mesures | 42 |
| `--correlation-id` | `x-correlation-id` envoyé en métadonnée gRPC | aléatoire |

## Tests

```bash
python -m unittest test_scenarios -v
```

Les tests vérifient (avec une réimplémentation indépendante du barème NEWS2) que `normal` ne dépasse jamais un score bas,
que `crise_cardiaque` atteint le seuil critique en moins de 40 mesures, que `detresse_respiratoire` passe par une phase
intermédiaire avant de devenir critique, que `chute` signale la chute puis revient au calme, et que toutes les valeurs
restent dans les limites acceptées par le serveur.
