# Contexte de build : la racine du dépôt (le contrat telemetry.proto vit dans /contracts)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY contracts/telemetry.proto ./contracts/telemetry.proto
COPY services/grpc-telemetry/pom.xml ./services/grpc-telemetry/pom.xml
COPY services/grpc-telemetry/src ./services/grpc-telemetry/src
WORKDIR /build/services/grpc-telemetry
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/services/grpc-telemetry/target/app.jar app.jar
EXPOSE 9090 9092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
