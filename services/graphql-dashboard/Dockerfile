# Contexte de build : la racine du dépôt (les contrats vivent dans /contracts)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY contracts/telemetry.proto contracts/dashboard.graphql ./contracts/
COPY services/graphql-dashboard/pom.xml ./services/graphql-dashboard/pom.xml
COPY services/graphql-dashboard/src ./services/graphql-dashboard/src
WORKDIR /build/services/graphql-dashboard
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/services/graphql-dashboard/target/app.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
