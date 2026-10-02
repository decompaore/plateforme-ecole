# syntax=docker/dockerfile:1
# =====================================================================
# Image de l'API – construction en deux étapes
#   1. compilation avec Maven (les tests sont exécutés par la CI, pas ici)
#   2. image d'exécution légère, utilisateur non root
# =====================================================================
FROM maven:3.9-eclipse-temurin-21 AS construction
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q package -DskipTests

FROM eclipse-temurin:24-jre
LABEL org.opencontainers.image.title="plateforme-api" \
      org.opencontainers.image.description="API de la plateforme SaaS de gestion des établissements secondaires"
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=construction /src/target/plateforme-api.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
# Vérifie que le port de supervision répond (bash est présent dans l'image)
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD bash -c '</dev/tcp/127.0.0.1/8081' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
