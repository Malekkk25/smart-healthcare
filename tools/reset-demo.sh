#!/usr/bin/env bash
# Remet la plateforme dans l'état de départ de la démo, SANS effacer les données de référence :
# ferme les alertes ouvertes, libère les ambulances, annule les rendez-vous d'urgence en attente.
# Usage : bash tools/reset-demo.sh
set -u
cd "$(dirname "$0")/.."
sql() { docker compose exec -T postgres psql -q -U postgres -d "$1" -c "$2"; }

sql telemetry_db "UPDATE alert SET status = 'CLOSED' WHERE status = 'OPEN'"
sql portal_db "UPDATE ambulance SET statut = 'DISPONIBLE', occupee_jusqua = NULL WHERE id IN ('AMB-01', 'AMB-02', 'AMB-03')"
sql workflow_db "UPDATE pending_task SET status = 'CANCELLED' WHERE status = 'PENDING'"
echo "Démo réinitialisée : alertes fermées, 3 ambulances disponibles, tâches différées annulées."
echo "(Pour tout effacer, y compris l'historique des interventions : docker compose down -v)"
