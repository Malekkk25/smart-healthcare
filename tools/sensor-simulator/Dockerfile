# Contexte de build : la racine du dépôt (le contrat telemetry.proto vit dans /contracts)
FROM python:3.11-slim
WORKDIR /app
COPY tools/sensor-simulator/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY contracts/telemetry.proto /contracts/telemetry.proto
COPY contracts/seed/patients.json /contracts/seed/patients.json
COPY tools/sensor-simulator/ .
RUN sh gen_stubs.sh /contracts
ENV PATIENTS_FILE=/contracts/seed/patients.json
ENTRYPOINT ["python", "simulate.py"]
