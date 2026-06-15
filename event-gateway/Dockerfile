# ---- Build stage ----
# Build context is the repository root so the parent POM and both modules are available.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
RUN mvn -B -pl event-gateway -am dependency:go-offline
COPY event-gateway/src event-gateway/src
RUN mvn -B -pl event-gateway -am clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/event-gateway/target/event-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
