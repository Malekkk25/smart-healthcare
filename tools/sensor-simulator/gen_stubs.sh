#!/usr/bin/env sh
# Génère telemetry_pb2.py et telemetry_pb2_grpc.py à partir de contracts/telemetry.proto
# Usage : sh gen_stubs.sh [dossier_des_contrats]   (par défaut ../../contracts)
set -e
cd "$(dirname "$0")"
CONTRACTS="${1:-../../contracts}"
python -m grpc_tools.protoc -I "$CONTRACTS" --python_out=. --grpc_python_out=. "$CONTRACTS/telemetry.proto"
echo "stubs générés"
