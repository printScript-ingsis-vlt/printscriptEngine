# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilar ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY . .
RUN chmod +x gradlew

# El token de GitHub Packages se pasa como secret de BuildKit: no queda en ninguna capa de la imagen.
# El cache de ~/.gradle evita volver a bajar todas las dependencias en cada build.
ARG GITHUB_USER
RUN --mount=type=secret,id=gpr_token \
    --mount=type=cache,target=/root/.gradle \
    GITHUB_USER="$GITHUB_USER" GITHUB_TOKEN="$(cat /run/secrets/gpr_token)" \
    ./gradlew bootJar --no-daemon -x test

# ---------- Etapa 2: ejecutar ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

# curl para el healthcheck del compose (GET /actuator/health). Se instala solo si la imagen base no lo trae.
RUN command -v curl >/dev/null || \
    (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*)

COPY --from=build /src/build/libs/*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]