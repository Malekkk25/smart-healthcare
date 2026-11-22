import json
import random

random.seed(42)

prenoms_h = ["Ahmed", "Mohamed", "Youssef", "Karim", "Walid", "Amine"]
prenoms_f = ["Sarra", "Ines", "Rim", "Mariem", "Hela", "Nour"]
noms = ["Ben Salah", "Trabelsi", "Jlassi", "Gharbi", "Mansouri", "Chaabane", "Bouzid", "Hamdi"]
pathologies = {
    "cardiologie": ["Insuffisance cardiaque", "Arythmie"],
    "pneumologie": ["BPCO", "Asthme sévère"],
    "geriatrie": ["Hypertension", "Chute à répétition"],
    "medecine generale": ["Diabète de type 2", "Hypertension"],
}

patients = []
for i in range(1, 21):
    sexe = random.choice(["M", "F"])
    prenom = random.choice(prenoms_h if sexe == "M" else prenoms_f)
    service = random.choice(list(pathologies))
    patients.append({
        "id": f"PAT-{i:06d}",
        "nom": random.choice(noms),
        "prenom": prenom,
        "sexe": sexe,
        "dateNaissance": f"{random.randint(1940, 2000)}-{random.randint(1, 12):02d}-{random.randint(1, 28):02d}",
        "service": service,
        "pathologie": random.choice(pathologies[service]),
        "niveauRisque": random.choice(["FAIBLE", "MOYEN", "ELEVE"]),
        "numeroAssure": str(random.randint(10**9, 10**10 - 1)),
        "tauxRemboursement": random.choice([80, 50]),
    })

with open("contracts/seed/patients.json", "w", encoding="utf-8") as f:
    json.dump(patients, f, ensure_ascii=False, indent=2)

print(f"{len(patients)} patients générés")