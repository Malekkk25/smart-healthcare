"""Convertit contracts/seed/patients.json en scripts SQL pour ehr_db et portal_db.

Usage (depuis la racine du dépôt) : python contracts/seed/generate_sql.py
"""
import json

EXPIRES = {"PAT-000010", "PAT-000020"}  # droits CNAM expirés (cas d'erreur volontaires)


def q(value):
    return "'" + str(value).replace("'", "''") + "'"


with open("contracts/seed/patients.json", encoding="utf-8") as f:
    patients = json.load(f)

ehr = ["-- Généré par contracts/seed/generate_sql.py : ne pas modifier à la main"]
portal = ["-- Généré par contracts/seed/generate_sql.py : ne pas modifier à la main"]

for p in patients:
    fin_droits = "2026-06-30" if p["id"] in EXPIRES else "2027-12-31"
    ehr.append(
        "INSERT INTO patient (id, nom, prenom, numero_assure, taux_remboursement, date_fin_droits) VALUES "
        f"({q(p['id'])}, {q(p['nom'])}, {q(p['prenom'])}, {q(p['numeroAssure'])}, {p['tauxRemboursement']}, {q(fin_droits)}) "
        "ON CONFLICT (id) DO NOTHING;"
    )
    portal.append(
        "INSERT INTO patient (id, nom, prenom, sexe, date_naissance, service, pathologie, niveau_risque, numero_assure, taux_remboursement) VALUES "
        f"({q(p['id'])}, {q(p['nom'])}, {q(p['prenom'])}, {q(p['sexe'])}, {q(p['dateNaissance'])}, {q(p['service'])}, "
        f"{q(p['pathologie'])}, {q(p['niveauRisque'])}, {q(p['numeroAssure'])}, {p['tauxRemboursement']}) "
        "ON CONFLICT (id) DO NOTHING;"
    )

targets = {
    "services/soap-ehr/src/main/resources/data-patients.sql": ehr,
    "services/rest-portal/src/main/resources/data-patients.sql": portal,
}
for path, lines in targets.items():
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(lines) + "\n")
    print(f"{path} : {len(lines) - 1} patients")
