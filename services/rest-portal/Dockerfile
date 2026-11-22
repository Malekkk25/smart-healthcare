# Contexte de build : la racine du dépôt
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY services/rest-portal/pom.xml ./pom.xml
COPY services/rest-portal/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
