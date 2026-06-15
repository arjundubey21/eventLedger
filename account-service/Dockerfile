# ---- Build stage ----
# Build context is the repository root so the parent POM and both modules are available.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
# Resolve dependencies first for better layer caching.
RUN mvn -B -pl account-service -am dependency:go-offline
COPY account-service/src account-service/src
RUN mvn -B -pl account-service -am clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/account-service/target/account-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
