# Contexte de build : la racine du dépôt (le contrat WSDL vit dans /contracts)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY contracts/ehr ./contracts/ehr
COPY services/soap-ehr/pom.xml ./services/soap-ehr/pom.xml
COPY services/soap-ehr/src ./services/soap-ehr/src
WORKDIR /build/services/soap-ehr
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/services/soap-ehr/target/app.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
