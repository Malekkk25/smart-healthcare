#!/usr/bin/env bash
# Test de résilience : coupe tour à tour le SOAP, le REST, le GraphQL et le service IA PENDANT une intervention,
# vérifie le comportement prévu (mode dégradé, replis, rejeu) puis relance le service.
# Prérequis : docker compose up -d (plateforme complète), curl, jq.
# Usage : bash tools/chaos-demo.sh [URL_GATEWAY]
set -u
GW="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
PASS="$(grep -E '^DEMO_PASSWORD=' .env | cut -d= -f2-)"
TOKEN=$(curl -s -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"admin\",\"password\":\"$PASS\"}" | jq -r .token)
ok=0; ko=0

check() { # check "description" attendu obtenu
  if [ "$2" = "$3" ]; then ok=$((ok+1)); printf '  \033[32mOK\033[0m   %s\n' "$1"
  else ko=$((ko+1)); printf '  \033[31mKO\033[0m   %s (attendu: %s, obtenu: %s)\n' "$1" "$2" "$3"; fi
}
api() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }
new_uuid() { python -c 'import uuid;print(uuid.uuid4())'; }

reset_ambulances() { # état de départ identique pour chaque scénario (3 ambulances disponibles)
  docker compose exec -T postgres psql -q -U postgres -d portal_db -c     "UPDATE ambulance SET statut='DISPONIBLE', occupee_jusqua=NULL WHERE id IN ('AMB-01','AMB-02','AMB-03')" >/dev/null 2>&1
}
trigger() { # trigger -> identifiant de l'intervention
  reset_ambulances
  api -X POST "$GW/api/orchestrator/workflows" -H 'Content-Type: application/json' \
      -d "{\"alertId\":\"$(new_uuid)\",\"patientId\":\"${1:-PAT-000004}\"}" | jq -r .id
}
wf() { api "$GW/api/orchestrator/workflows/$1"; }
wait_end() { # attend que l'intervention ne soit plus RUNNING (60 s max)
  for _ in $(seq 1 60); do [ "$(wf "$1" | jq -r .status)" != RUNNING ] && return 0; sleep 1; done; return 1
}
step() { wf "$1" | jq -r --arg n "$2" '.steps[] | select(.name==$n) | .status'; }
wait_step() { # wait_step id etape statut [secondes] : attend un statut d'étape (rejeu différé)
  for _ in $(seq 1 "${4:-120}"); do [ "$(step "$1" "$2")" = "$3" ] && return 0; sleep 1; done; return 1
}
wait_up() { # attend qu'un service réponde (santé)
  for _ in $(seq 1 90); do curl -s -o /dev/null -f "$1" && return 0; sleep 1; done; return 1
}

echo "== 0. Référence : tout fonctionne"
ID=$(trigger); wait_end "$ID"
check "intervention nominale" COMPLETED "$(wf "$ID" | jq -r .status)"

echo "== 1. SOAP coupé (service EHR)"
docker compose stop soap-ehr >/dev/null 2>&1
ID=$(trigger); wait_end "$ID"
check "étape DROITS en mode dégradé" DEGRADED "$(step "$ID" DROITS)"
check "les secours partent quand même (PRISE_EN_CHARGE)" SUCCEEDED "$(step "$ID" PRISE_EN_CHARGE)"
check "la fiche est produite" SUCCEEDED "$(step "$ID" FICHE)"
check "statut global dégradé (pas d'échec)" DEGRADED "$(wf "$ID" | jq -r .status)"
docker compose start soap-ehr >/dev/null 2>&1; wait_up "http://localhost:8081/actuator/health"
if wait_step "$ID" DROITS SUCCEEDED 120; then r=SUCCEEDED; else r="$(step "$ID" DROITS)"; fi
check "droits vérifiés après coup au retour du SOAP" SUCCEEDED "$r"
check "statut final de l'intervention" COMPLETED "$(wf "$ID" | jq -r .status)"

echo "== 2. GraphQL coupé (fiche différée)"
docker compose stop graphql-dashboard >/dev/null 2>&1
ID=$(trigger); wait_end "$ID"
check "étape FICHE différée" DEGRADED "$(step "$ID" FICHE)"
check "l'ambulance est partie malgré tout" SUCCEEDED "$(step "$ID" PRISE_EN_CHARGE)"
docker compose start graphql-dashboard >/dev/null 2>&1; wait_up "http://localhost:8083/actuator/health"
if wait_step "$ID" FICHE SUCCEEDED 150; then r=SUCCEEDED; else r="$(step "$ID" FICHE)"; fi
check "fiche rejouée au retour du GraphQL" SUCCEEDED "$r"
check "la fiche contient un résumé" true "$(wf "$ID" | jq '.dossier.triage.summary != null')"

echo "== 3. Service IA coupé (résumé de repli)"
docker compose stop ai-triage >/dev/null 2>&1
ID=$(trigger); wait_end "$ID"
check "fiche produite sans le service IA" SUCCEEDED "$(step "$ID" FICHE)"
check "résumé généré par le modèle de phrases local" template-orchestrateur "$(wf "$ID" | jq -r .dossier.triage.source)"
docker compose start ai-triage >/dev/null 2>&1

echo "== 4. REST coupé (portail) : 3 essais, repli, alerte admin"
docker compose stop rest-portal >/dev/null 2>&1
ID=$(trigger); wait_end "$ID"
check "PRISE_EN_CHARGE : échec après 3 essais" FAILED "$(step "$ID" PRISE_EN_CHARGE)"
check "nombre d'essais" 3 "$(wf "$ID" | jq '[.steps[] | select(.name=="PRISE_EN_CHARGE")][0].attempts')"
check "alerte administrateur levée" true "$(wf "$ID" | jq '.adminAlert != null')"
check "statut global : échec" FAILED "$(wf "$ID" | jq -r .status)"
docker compose start rest-portal >/dev/null 2>&1; wait_up "http://localhost:8082/actuator/health"
if wait_step "$ID" FICHE SUCCEEDED 150; then r=SUCCEEDED; else r="$(step "$ID" FICHE)"; fi
check "la fiche en attente est rejouée au retour du REST" SUCCEEDED "$r"

echo "== 5. Idempotence : la même alerte ne relance pas le workflow"
A=$(new_uuid)
R1=$(api -X POST "$GW/api/orchestrator/workflows" -H 'Content-Type: application/json' -d "{\"alertId\":\"$A\",\"patientId\":\"PAT-000004\"}")
C2=$(api -o /tmp/chaos_body -w '%{http_code}' -X POST "$GW/api/orchestrator/workflows" -H 'Content-Type: application/json' -d "{\"alertId\":\"$A\",\"patientId\":\"PAT-000004\"}")
check "second POST : 200 (intervention existante)" 200 "$C2"
check "même intervention" "$(echo "$R1" | jq -r .id)" "$(jq -r .id /tmp/chaos_body)"

echo
echo "Résultat : $ok réussis, $ko échecs"
[ "$ko" -eq 0 ]
