#!/usr/bin/env bash
# Test de bout en bout de la Partie 1 (A), à travers la Gateway : sécurité, REST, SOAP, urgences.
# Prérequis : docker compose up -d (postgres, soap-ehr, rest-portal, gateway), curl et jq.
# Usage : bash tools/smoke-test-a.sh [URL_GATEWAY]
set -u
GW="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
PASS="$(grep -E '^DEMO_PASSWORD=' .env | cut -d= -f2-)"

ok=0; ko=0
BODY=""; CODE=""

req() { # req METHOD PATH [JSON] [TOKEN] [CONTENT_TYPE]
  local m="$1" p="$2" d="${3:-}" t="${4:-}" ct="${5:-application/json}"
  local args=(-s -o /tmp/sh_body -w '%{http_code}' -X "$m" "$GW$p" -H "X-Correlation-Id: smoke-$RANDOM")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$d" ] && args+=(-H "Content-Type: $ct" --data-binary "$d")
  CODE=$(curl "${args[@]}"); BODY=$(cat /tmp/sh_body)
}
check() { # check "description" expected actual
  if [ "$2" = "$3" ]; then ok=$((ok+1)); printf '  \033[32mOK\033[0m   %s\n' "$1"
  else ko=$((ko+1)); printf '  \033[31mKO\033[0m   %s (attendu: %s, obtenu: %s)\n' "$1" "$2" "$3"; fi
}
login() { req POST /api/auth/login "{\"username\":\"$1\",\"password\":\"$PASS\"}"; echo "$BODY" | jq -r .token; }
xv() { python -c 'import sys,xml.etree.ElementTree as E
r=E.fromstring(sys.stdin.read())
t=lambda n: next((e.text or "" for e in r.iter() if e.tag.split("}")[-1]==n), "")
print(" ".join(t(n) for n in sys.argv[1:]))' "$@"; }
xc() { python -c 'import sys,xml.etree.ElementTree as E
r=E.fromstring(sys.stdin.read())
print(sum(1 for e in r.iter() if e.tag.split("}")[-1]==sys.argv[1]))' "$1"; }
soap() { # soap OPERATION '<ehr:champ>..</ehr:champ>' TOKEN
  req POST /api/ehr "<?xml version=\"1.0\"?><soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ehr=\"http://smarthealthcare.tn/ehr\"><soapenv:Body><ehr:$1Request>$2</ehr:$1Request></soapenv:Body></soapenv:Envelope>" "$3" "text/xml; charset=utf-8"
}

echo "== Authentification"
req POST /api/auth/login "{\"username\":\"patient1\",\"password\":\"mauvais\"}"
check "login refusé (mauvais mot de passe) -> 401" 401 "$CODE"
check "erreur au format commun (code)" NON_AUTHENTIFIE "$(echo "$BODY" | jq -r .code)"
check "erreur au format commun (correlationId présent)" true "$(echo "$BODY" | jq 'has("correlationId")')"
TP=$(login patient1); TP2=$(login patient2); TM=$(login medecin); TA=$(login admin)
check "jeton patient1 émis" true "$([ "${#TP}" -gt 40 ] && echo true || echo false)"
check "claims du jeton (role, patientId)" "PATIENT PAT-000002" "$(echo "$TP" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | jq -r '"\(.role) \(.patientId)"')"
req GET /api/portal/slots
check "sans jeton -> 401" 401 "$CODE"
req GET /api/portal/slots "" "jeton.invalide.xxx"
check "jeton falsifié -> 401" 401 "$CODE"

echo "== Rôles et accès aux données"
req GET /api/portal/patients/PAT-000002 "" "$TP"; check "patient1 lit son profil -> 200" 200 "$CODE"
req GET /api/portal/patients/PAT-000004 "" "$TP"; check "patient1 lit le profil d'un autre -> 403" 403 "$CODE"
req GET "/api/portal/appointments?patientId=PAT-000004" "" "$TP"; check "patient1 liste les RDV d'un autre -> 403" 403 "$CODE"
req GET /api/portal/patients "" "$TP"; check "patient liste tous les patients -> 403" 403 "$CODE"
req GET /api/portal/patients "" "$TM"; check "médecin liste les patients -> 200" 200 "$CODE"
check "20 patients" 20 "$(echo "$BODY" | jq length)"
req GET "/api/portal/patients?service=cardiologie&niveauRisque=ELEVE" "" "$TM"
check "filtres service + risque (tous cardiologie/ELEVE)" true "$(echo "$BODY" | jq 'all(.[]; .service=="cardiologie" and .niveauRisque=="ELEVE")')"
req GET /api/portal/ambulances "" "$TP"; check "patient liste les ambulances -> 403" 403 "$CODE"
req PUT /api/portal/patients/PAT-000002 '{"nom":"X","prenom":"Y","sexe":"M","dateNaissance":"1950-01-01"}' "$TM"
check "médecin modifie un patient -> 403" 403 "$CODE"

echo "== REST : créneaux et rendez-vous"
DEMAIN=$(date -d tomorrow +%F 2>/dev/null || date -v+1d +%F)
req GET "/api/portal/slots?doctorId=DOC-001&date=$DEMAIN" "" "$TP"
check "créneaux du lendemain -> 200" 200 "$CODE"
SLOT=$(echo "$BODY" | jq -r '[.[] | select(.urgence==false)][0].id')
check "un créneau libre existe" true "$([ -n "$SLOT" ] && [ "$SLOT" != null ] && echo true || echo false)"
req POST /api/portal/appointments "{\"patientId\":\"PAT-000002\",\"slotId\":\"$SLOT\",\"motif\":\"Test de bout en bout\"}" "$TP"
check "création de rendez-vous -> 201" 201 "$CODE"
APT=$(echo "$BODY" | jq -r .id)
check "identifiant APT-AAAA-NNNNNN" true "$(echo "$APT" | grep -Eq '^APT-[0-9]{4}-[0-9]{6}$' && echo true || echo false)"
req POST /api/portal/appointments "{\"patientId\":\"PAT-000002\",\"slotId\":\"$SLOT\"}" "$TP"
check "même créneau réservé deux fois -> 409" 409 "$CODE"
check "code CRENEAU_INDISPONIBLE" CRENEAU_INDISPONIBLE "$(echo "$BODY" | jq -r .code)"
req POST /api/portal/appointments "{\"patientId\":\"PAT-000004\",\"slotId\":\"$SLOT\"}" "$TP"
check "patient1 réserve pour un autre patient -> 403" 403 "$CODE"
req POST /api/portal/appointments '{"patientId":"oups"}' "$TP"
check "corps invalide -> 400" 400 "$CODE"
req GET "/api/portal/appointments?patientId=PAT-000002" "" "$TP"
check "le RDV apparaît dans la liste du patient" true "$(echo "$BODY" | jq --arg a "$APT" 'any(.[]; .id==$a)')"
req PUT "/api/portal/appointments/$APT" '{"motif":"Motif modifie"}' "$TP"
check "modification du motif -> 200" "Motif modifie" "$(echo "$BODY" | jq -r .motif)"
req GET "/api/portal/appointments/$APT" "" "$TP2"
check "patient2 lit le RDV de patient1 -> 403" 403 "$CODE"
req DELETE "/api/portal/appointments/$APT" "" "$TP"; check "annulation -> 204" 204 "$CODE"
req GET "/api/portal/slots?doctorId=DOC-001&date=$DEMAIN" "" "$TP"
check "le créneau est de nouveau libre" true "$(echo "$BODY" | jq --arg s "$SLOT" 'any(.[]; .id==$s)')"
req GET /api/portal/appointments/APT-0000-000000 "" "$TM"; check "RDV inconnu -> 404" 404 "$CODE"
req GET /api/portal/patients/PAT-000002/prescriptions "" "$TP"
check "ordonnances de PAT-000002 (>=1, médicaments présents)" true "$(echo "$BODY" | jq 'length>=1 and (.[0].medicaments|length)>=1')"

echo "== SOAP via la Gateway (relais)"
soap verifierDroitsCNAM '<ehr:patientId>PAT-000002</ehr:patientId><ehr:codeActe>CS</ehr:codeActe>' "$TP"
check "verifierDroitsCNAM -> 200" 200 "$CODE"
check "droitsOuverts=true et taux=80" "true 80" "$(echo "$BODY" | xv droitsOuverts tauxRemboursement)"
soap verifierDroitsCNAM '<ehr:patientId>PAT-000002</ehr:patientId><ehr:codeActe>XX</ehr:codeActe>' "$TP"
check "acte hors nomenclature -> droitsOuverts=false" false "$(echo "$BODY" | xv droitsOuverts)"
soap verifierDroitsCNAM '<ehr:patientId>PAT-000010</ehr:patientId><ehr:codeActe>C</ehr:codeActe>' "$TP"
check "droits expirés -> SOAP Fault 500" 500 "$CODE"
check "détail DroitsExpiresFault" true "$(echo "$BODY" | grep -q 'DroitsExpiresFault' && echo true || echo false)"
soap verifierDroitsCNAM '<ehr:patientId>PAT-000099</ehr:patientId><ehr:codeActe>C</ehr:codeActe>' "$TP"
check "patient inconnu -> SOAP Fault 500" 500 "$CODE"
check "détail PatientInconnuFault" true "$(echo "$BODY" | grep -q 'PatientInconnuFault' && echo true || echo false)"
soap emettreFeuilleDeSoins '<ehr:patientId>PAT-000002</ehr:patientId><ehr:codeActe>CS</ehr:codeActe><ehr:dateActe>2026-11-20</ehr:dateActe><ehr:montant>35.000</ehr:montant><ehr:praticienId>DOC-001</ehr:praticienId>' "$TP"
check "emettreFeuilleDeSoins -> 200" 200 "$CODE"
check "remboursé 28.000 (35 x 80 %), statut EMISE" "28.000 EMISE" "$(echo "$BODY" | xv montantRembourse statut)"
soap consulterHistorique '<ehr:patientId>PAT-000002</ehr:patientId>' "$TP"
check "historique contient au moins 3 actes" true "$([ "$(echo "$BODY" | xc acte)" -ge 3 ] && echo true || echo false)"
soap comparerGaranties '<ehr:patientId>PAT-000002</ehr:patientId><ehr:priseEnChargeIdA>PEC-2025-001</ehr:priseEnChargeIdA><ehr:priseEnChargeIdB>PEC-2026-007</ehr:priseEnChargeIdB>' "$TP"
check "comparerGaranties : 3 différences" 3 "$(echo "$BODY" | xc difference)"
soap verifierDroitsCNAM '<ehr:patientId>PAT-000002</ehr:patientId><ehr:codeActe>C</ehr:codeActe>' ""
check "SOAP sans jeton -> 401" 401 "$CODE"
req GET "/api/ehr?wsdl" "" "$TP"
check "WSDL servi via la Gateway" true "$(echo "$BODY" | grep -q 'EhrService' && echo true || echo false)"

echo "== Urgences (POST /emergency/dispatch)"
ALERT=$(python -c 'import uuid;print(uuid.uuid4())')
PAYLOAD="{\"patientId\":\"PAT-000004\",\"alertId\":\"$ALERT\",\"severity\":\"CRITIQUE\",\"location\":{\"latitude\":36.8065,\"longitude\":10.1815}}"
req POST /api/portal/emergency/dispatch "$PAYLOAD" "$TP"; check "patient déclenche une urgence -> 403" 403 "$CODE"
req POST /api/portal/emergency/dispatch "$PAYLOAD" "$TM"; check "médecin déclenche une urgence -> 201" 201 "$CODE"
D1=$(echo "$BODY" | jq -r .dispatchId)
check "réponse : dispatchId + (ambulanceId et eta) ou fallbackAppointmentId" true "$(echo "$BODY" | jq '(.dispatchId|test("^DSP-")) and ((.ambulanceId!=null and .etaMinutes>=1) or (.fallbackAppointmentId!=null))')"
req POST /api/portal/emergency/dispatch "$PAYLOAD" "$TM"
check "idempotence : même alertId -> même dispatchId" "$D1" "$(echo "$BODY" | jq -r .dispatchId)"
FALLBACK=""
for i in 1 2 3 4 5; do
  req POST /api/portal/emergency/dispatch "{\"patientId\":\"PAT-00000$i\",\"alertId\":\"smoke-$RANDOM-$i-$$\",\"severity\":\"ELEVEE\",\"location\":{\"latitude\":36.80,\"longitude\":10.18}}" "$TM"
  FALLBACK=$(echo "$BODY" | jq -r '.fallbackAppointmentId // empty'); [ -n "$FALLBACK" ] && break
done
check "ambulances épuisées -> bascule sur un créneau d'urgence" true "$([ -n "$FALLBACK" ] && echo true || echo false)"
check "repli : ambulanceId et etaMinutes nuls" "null null" "$(echo "$BODY" | jq -r '"\(.ambulanceId) \(.etaMinutes)"')"
req POST /api/portal/emergency/dispatch '{"patientId":"PAT-000999","alertId":"x-unknown","severity":"CRITIQUE","location":{"latitude":36.8,"longitude":10.1}}' "$TM"
check "patient inconnu -> 404" 404 "$CODE"
req POST /api/portal/emergency/dispatch '{"patientId":"PAT-000004","alertId":"x-bad","severity":"URGENT","location":{"latitude":36.8,"longitude":10.1}}' "$TM"
check "gravité invalide -> 400" 400 "$CODE"
req GET /api/portal/ambulances "" "$TA"
check "ambulances listées avec position" true "$(echo "$BODY" | jq 'length==5 and all(.[]; .position.latitude!=null)')"

echo "== Routes de B (services non démarrés) et erreurs"
req GET /api/orchestrator/workflows/x "" "$TP"; check "patient -> orchestrateur : 403" 403 "$CODE"
req GET /api/orchestrator/workflows/x "" "$TA"
if [ "$CODE" = 503 ]; then
  check "orchestrateur arrêté -> 503 SERVICE_INDISPONIBLE" SERVICE_INDISPONIBLE "$(echo "$BODY" | jq -r .code)"
else
  echo "  --   orchestrateur répond (code $CODE) : service de B démarré"
fi
req GET /api/inconnu "" "$TA"; check "route inconnue -> 404 au format commun" RESSOURCE_INTROUVABLE "$(echo "$BODY" | jq -r .code)"
curl -s -D - -o /dev/null "$GW/actuator/health" -H "X-Correlation-Id: abc-123" | grep -qi '^X-Correlation-Id: abc-123' \
  && check "X-Correlation-Id repris dans la réponse" ok ok || check "X-Correlation-Id repris dans la réponse" ok ko

echo
echo "Résultat : $ok réussis, $ko échecs"
[ "$ko" -eq 0 ]
