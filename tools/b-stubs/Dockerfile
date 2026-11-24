# Contexte de build : la racine du dépôt (le schéma GraphQL vit dans /contracts)
FROM node:22-alpine
WORKDIR /app
COPY tools/b-stubs/package.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY tools/b-stubs/server.js ./
COPY contracts/dashboard.graphql ./dashboard.graphql
EXPOSE 8083 8085
