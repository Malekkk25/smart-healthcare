#!/usr/bin/env python
"""Simulateur de capteurs : envoie des constantes vitales au service gRPC (StreamVitals).

Exemples :
  python simulate.py --scenario PAT-000004:crise_cardiaque --speed 5
  python simulate.py --scenario PAT-000004:crise_cardiaque --scenario PAT-000002:detresse_respiratoire --speed 10
  python simulate.py --patients PAT-000001,PAT-000003 --duration 60           # patients stables
  python simulate.py --all --duration 30 --speed 20                           # les 20 patients du jeu de données
"""
import argparse
import json
import os
import sys
import threading
import time
import uuid

import grpc

import telemetry_pb2 as pb
import telemetry_pb2_grpc as pb_grpc
from google.protobuf.timestamp_pb2 import Timestamp
from scenarios import SCENARIOS, Trajectoire

CONSCIENCE = {
    "ALERT": pb.ALERT, "CONFUSION": pb.CONFUSION, "VOICE": pb.VOICE, "PAIN": pb.PAIN, "UNRESPONSIVE": pb.UNRESPONSIVE,
}


def vers_message(m):
    t = Timestamp()
    t.GetCurrentTime()
    return pb.VitalSample(
        patient_id=m["patient_id"], measured_at=t, heart_rate=m["heart_rate"], resp_rate=m["resp_rate"],
        spo2=m["spo2"], systolic_bp=m["systolic_bp"], diastolic_bp=m["diastolic_bp"], temperature=m["temperature"],
        on_oxygen=m["on_oxygen"], consciousness=CONSCIENCE[m["consciousness"]], fall_detected=m["fall_detected"],
    )


def patients_du_jeu_de_donnees():
    chemin = os.environ.get("PATIENTS_FILE") or os.path.join(os.path.dirname(__file__), "..", "..", "contracts", "seed", "patients.json")
    with open(chemin, encoding="utf-8") as f:
        return [p["id"] for p in json.load(f)]


def envoyer(stub, patient, scenario, args, verrou, stop):
    """Un flux StreamVitals par patient ; une mesure toutes les (intervalle / vitesse) secondes."""
    traj = Trajectoire(patient, scenario, args.seed)
    pas = args.interval / args.speed

    def flux():
        etape = 0
        while not stop.is_set():
            mesure = traj.mesure(etape % args.cycle if args.loop else etape)
            with verrou:
                print(f"[{patient}] {scenario:<22} t={etape:>3}  FC {mesure['heart_rate']:>3}  FR {mesure['resp_rate']:>2}  "
                      f"SpO2 {mesure['spo2']:>5}  PAS {mesure['systolic_bp']:>3}  T {mesure['temperature']}  "
                      f"{mesure['consciousness']}{'  CHUTE' if mesure['fall_detected'] else ''}", flush=True)
            yield vers_message(mesure)
            etape += 1
            if not args.loop and etape >= args.duration:
                return
            stop.wait(pas)

    metadata = (("x-correlation-id", args.correlation_id),)
    try:
        resume = stub.StreamVitals(flux(), metadata=metadata)
        with verrou:
            print(f"[{patient}] terminé : {resume.accepted} acceptées, {resume.rejected} rejetées", flush=True)
    except grpc.RpcError as e:
        with verrou:
            print(f"[{patient}] erreur gRPC : {e.code()} {e.details()}", file=sys.stderr, flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default=os.environ.get("TELEMETRY_HOST", "localhost:9090"), help="adresse du service gRPC")
    p.add_argument("--scenario", action="append", default=[], metavar="PATIENT:SCENARIO",
                   help=f"scénario d'un patient ({', '.join(SCENARIOS)}) ; répétable")
    p.add_argument("--patients", default="", help="patients stables (scénario normal), séparés par des virgules")
    p.add_argument("--all", action="store_true", help="les 20 patients du jeu de données, en scénario normal")
    p.add_argument("--speed", type=float, default=1.0, help="accélération du temps (5 = 5 fois plus vite)")
    p.add_argument("--interval", type=float, default=1.0, help="secondes simulées entre deux mesures")
    p.add_argument("--duration", type=int, default=70, help="nombre de mesures par patient")
    p.add_argument("--loop", action="store_true", help="rejoue le scénario en boucle (Ctrl+C pour arrêter)")
    p.add_argument("--cycle", type=int, default=90, help="longueur du cycle en mode --loop (mesures)")
    p.add_argument("--seed", type=int, default=42, help="graine : même graine = mêmes mesures")
    p.add_argument("--correlation-id", default=str(uuid.uuid4()), help="x-correlation-id envoyé en métadonnée")
    args = p.parse_args()

    affectations = {}
    for pid in ([x for x in args.patients.split(",") if x] + (patients_du_jeu_de_donnees() if args.all else [])):
        affectations[pid] = "normal"
    for s in args.scenario:
        pid, _, sc = s.partition(":")
        if sc not in SCENARIOS:
            p.error(f"scénario inconnu « {sc} » (disponibles : {', '.join(SCENARIOS)})")
        affectations[pid] = sc
    if not affectations:
        p.error("aucun patient : utilisez --scenario, --patients ou --all")

    print(f"Connexion à {args.host} · {len(affectations)} patient(s) · vitesse x{args.speed} · graine {args.seed}")
    canal = grpc.insecure_channel(args.host)
    try:
        grpc.channel_ready_future(canal).result(timeout=15)
    except grpc.FutureTimeoutError:
        sys.exit(f"service gRPC injoignable sur {args.host}")
    stub = pb_grpc.TelemetryStub(canal)

    verrou, stop = threading.Lock(), threading.Event()
    threads = [threading.Thread(target=envoyer, args=(stub, pid, sc, args, verrou, stop), daemon=True)
               for pid, sc in affectations.items()]
    try:
        for t in threads:
            t.start()
        for t in threads:
            while t.is_alive():
                t.join(0.5)
    except KeyboardInterrupt:
        stop.set()
        for t in threads:
            t.join(3)
        print("\narrêt demandé")


if __name__ == "__main__":
    main()
