# Contexte de build : la racine du dépôt (le contrat telemetry.proto vit dans /contracts)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY contracts/telemetry.proto ./contracts/telemetry.proto
COPY services/orchestrator/pom.xml ./services/orchestrator/pom.xml
COPY services/orchestrator/src ./services/orchestrator/src
WORKDIR /build/services/orchestrator
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/services/orchestrator/target/app.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
