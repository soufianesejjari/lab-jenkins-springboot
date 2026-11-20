# syntax=docker/dockerfile:1
# =============================================================================
# Image de l'API tasks-api — build multi-étapes
#   Étape 1 (build)   : Maven + JDK 21 compilent le jar
#   Étape 2 (runtime) : uniquement un JRE 21 + le jar (image plus petite, moins de failles)
# =============================================================================

# ---------- Étape 1 : build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 1) On copie d'abord le pom.xml seul et on télécharge les dépendances.
#    Tant que le pom ne change pas, Docker réutilise cette couche en cache
#    => les rebuilds suivants sont beaucoup plus rapides.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 2) Puis le code source (change souvent => invalide seulement les couches suivantes)
COPY config ./config
COPY src ./src

# Les tests, le lint et la couverture ont déjà été exécutés par le pipeline :
# ici on ne fait que packager.
RUN mvn -B package -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true

# ---------- Étape 2 : runtime ----------
FROM eclipse-temurin:21-jre-alpine

ARG APP_VERSION=dev
ARG APP_COMMIT=unknown
# Visibles dans /actuator/info (info.app.version / info.app.commit)
ENV APP_VERSION=${APP_VERSION} \
    APP_COMMIT=${APP_COMMIT}

LABEL org.opencontainers.image.title="tasks-api" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_COMMIT}"

# Utilisateur non-root : si l'application est compromise, l'attaquant n'est pas root
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/tasks-api.jar /app/app.jar
USER app

EXPOSE 8080

# Docker (et docker compose --wait) savent ainsi si le conteneur est réellement prêt
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
