#!/usr/bin/env bash
# Test de bout en bout de la chaîne d'urgence, de bout en bout :
#   capteurs -> gRPC (NEWS2, alertes) -> orchestrateur (4 étapes) -> fiche chez le médecin (GraphQL, SSE).
# Prérequis : docker compose up -d (plateforme complète), curl et jq.
# Usage : bash tools/smoke-test-b.sh [URL_GATEWAY]
set -u
GW="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
PASS="$(grep -E '^DEMO_PASSWORD=' .env | cut -d= -f2-)"
ok=0; ko=0
check() { # check "description" attendu obtenu
  if [ "$2" = "$3" ]; then ok=$((ok+1)); printf '  \033[32mOK\033[0m   %s\n' "$1"
  else ko=$((ko+1)); printf '  \033[31mKO\033[0m   %s (attendu: %s, obtenu: %s)\n' "$1" "$2" "$3"; fi
}
tok() { curl -s -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$PASS\"}" | jq -r .token; }
TA=$(tok admin); TM=$(tok medecin); TP=$(tok patient1)
adm() { curl -s -H "Authorization: Bearer $TA" "$@"; }
gql() { curl -s -X POST "$GW/api/dashboard/graphql" -H "Authorization: Bearer $TM" -H 'Content-Type: application/json' -d "$1"; }
sql() { docker compose exec -T postgres psql -q -U postgres -d "$1" -c "$2" >/dev/null 2>&1; }
simulate() { docker compose --profile demo run --rm -T sensor-simulator --host grpc-telemetry:9090 "$@" 2>&1; }

echo "== Santé des services"
for u in "8083/actuator/health" "8085/actuator/health" "9092/actuator/health" "8086/health"; do
  check "localhost:$u" UP "$(curl -s "localhost:$u" | jq -r .status)"
done

echo "== Préparation (état de départ identique)"
sql telemetry_db "UPDATE alert SET status='CLOSED' WHERE patient_id IN ('PAT-000004','PAT-000001')"
sql portal_db "UPDATE ambulance SET statut='DISPONIBLE', occupee_jusqua=NULL WHERE id IN ('AMB-01','AMB-02','AMB-03')"
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)

echo "== Scénario « normal » : aucune alerte"
simulate --scenario PAT-000001:normal --duration 15 --speed 100 | tail -1
R=$(gql '{"query":"{ patient(id: \"PAT-000001\") { latestVital { heartRate } alerts(status: OPEN) { id } } }"}')
check "constantes reçues et stockées (gRPC -> GraphQL)" true "$(echo "$R" | jq '.data.patient.latestVital.heartRate > 0')"
check "aucune alerte pour un patient stable" 0 "$(echo "$R" | jq '.data.patient.alerts | length')"

echo "== Scénario « crise cardiaque » sur PAT-000004"
simulate --scenario PAT-000004:crise_cardiaque --duration 45 --speed 30 | tail -1
echo "   (attente de l'intervention automatique...)"
ID=""
for _ in $(seq 1 60); do
  ID=$(adm "$GW/api/orchestrator/workflows?patientId=PAT-000004&limit=5" | jq -r --arg s "$START" '[.[] | select(.startedAt >= $s)][0].id // empty')
  [ -n "$ID" ] && [ "$(adm "$GW/api/orchestrator/workflows/$ID" | jq -r .status)" != RUNNING ] && break
  sleep 1
done
check "une intervention a été déclenchée automatiquement" true "$([ -n "$ID" ] && echo true || echo false)"
W=$(adm "$GW/api/orchestrator/workflows/$ID")
check "étapes dans l'ordre" "ALERTE DROITS PRISE_EN_CHARGE FICHE" "$(echo "$W" | jq -r '[.steps[].name] | join(" ")')"
check "1. alerte gRPC" SUCCEEDED "$(echo "$W" | jq -r '.steps[0].status')"
check "2. droits (SOAP)" SUCCEEDED "$(echo "$W" | jq -r '.steps[1].status')"
check "3. ambulance (REST)" SUCCEEDED "$(echo "$W" | jq -r '.steps[2].status')"
check "4. fiche (GraphQL + IA)" SUCCEEDED "$(echo "$W" | jq -r '.steps[3].status')"
check "intervention terminée" COMPLETED "$(echo "$W" | jq -r .status)"
check "ambulance affectée" true "$(echo "$W" | jq '.ambulanceId != null and .etaMinutes >= 1')"
check "correlation-id présent" true "$(echo "$W" | jq '.correlationId | length > 10')"
check "alerte d'origine critique" CRITIQUE "$(echo "$W" | jq -r .severity)"
check "fiche : risque élevé ou critique" true "$(echo "$W" | jq -r '.dossier.triage.riskLevel' | grep -qE '^(CRITIQUE|ELEVEE)$' && echo true || echo false)"
check "fiche : résumé mentionnant la patiente" true "$(echo "$W" | jq '.dossier.triage.summary | contains("Bouzid")')"
check "fiche : résumé produit par le service IA (pas par le repli local)" true "$(echo "$W" | jq -r '.dossier.triage.source' | grep -qE '^(llm|modele-de-phrases)$' && echo true || echo false)"
check "fiche : constantes incluses" true "$(echo "$W" | jq '.dossier.patient.latestVital.heartRate > 0')"
check "journal d'exécution (>= 4 lignes)" true "$(adm "$GW/api/orchestrator/workflows/$ID/journal" | jq 'length >= 4')"

echo "== Côté médecin"
R=$(gql '{"query":"{ patient(id: \"PAT-000004\") { nom alerts(status: OPEN) { severity news2Score } latestVital { heartRate spo2 } } }"}')
check "alerte ouverte visible en GraphQL" true "$(echo "$R" | jq '[.data.patient.alerts[] | select(.severity=="CRITIQUE" and .news2Score >= 7)] | length >= 1')"
check "constantes dégradées visibles" true "$(echo "$R" | jq '.data.patient.latestVital.heartRate > 100')"
check "le médecin lit l'intervention" 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TM" "$GW/api/orchestrator/workflows/$ID")"
check "un patient n'y a pas accès" 403 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TP" "$GW/api/orchestrator/workflows/$ID")"

echo "== Idempotence"
CODE=$(adm -o /tmp/smoke_b -w '%{http_code}' -X POST "$GW/api/orchestrator/workflows" -H 'Content-Type: application/json' \
  -d "{\"alertId\":\"$(echo "$W" | jq -r .alertId)\",\"patientId\":\"PAT-000004\"}")
check "même alerte : 200 et même intervention" "200 $ID" "$CODE $(jq -r .id /tmp/smoke_b)"

echo "== Flux SSE des étapes (via la Gateway)"
( timeout 12 curl -sN -H "Authorization: Bearer $TA" "$GW/api/orchestrator/workflows/events" > /tmp/smoke_b_sse ) &
sleep 2
adm -X POST "$GW/api/orchestrator/workflows" -H 'Content-Type: application/json' \
  -d "{\"alertId\":\"$(python -c 'import uuid;print(uuid.uuid4())')\",\"patientId\":\"PAT-000002\"}" -o /dev/null
wait
for e in WORKFLOW_STARTED STEP_UPDATED WORKFLOW_ENDED; do
  check "événement SSE $e reçu" true "$(grep -q "^event:$e\|^event: $e" /tmp/smoke_b_sse && echo true || echo false)"
done

echo "== Métriques"
check "alertes comptées par la télémétrie" true "$(curl -s localhost:9092/actuator/prometheus | grep -q '^telemetry_alerts_total' && echo true || echo false)"
check "interventions comptées par l'orchestrateur" true "$(curl -s localhost:8085/actuator/prometheus | grep -q '^orchestrator_workflows_total' && echo true || echo false)"

echo
echo "Résultat : $ok réussis, $ko échecs"
[ "$ko" -eq 0 ]
