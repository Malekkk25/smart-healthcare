# Contexte de build : la racine du dépôt
FROM python:3.11-slim
WORKDIR /app
COPY services/ai-triage/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/ai-triage/app ./app
EXPOSE 8086
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8086"]
