# =====================================================================
# Inventory Manager -- one deployable artifact
# =====================================================================
# The React build is compiled and bundled into the Spring Boot jar's static
# resources rather than shipped as a second container serving files. A separate
# static-file container buys nothing Spring Boot's own resource handling does
# not already do at this size, and it would be one more moving part for a small
# team to operate (Deployment Design §2).
# =====================================================================

# ---- Stage 1: build the frontend ------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- Stage 2: build the backend, with the frontend folded in --------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
# Warm the dependency cache so a source-only change does not re-resolve everything.
RUN mvn -f backend/pom.xml -B dependency:go-offline -DskipTests
COPY backend/ backend/
COPY --from=frontend /build/frontend/dist backend/src/main/resources/static/
# The frontend profile is deliberately not used here: the build above already
# produced dist/, so this stage only needs to package what it was handed.
RUN mvn -f backend/pom.xml -B -DskipTests package

# ---- Stage 3: runtime -----------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# pg_dump, for backups taken from inside the application (Settings > Backups).
# Pinned to 16 to match the server: pg_dump refuses to dump a newer server, and
# a mismatch surfaces as an unrestorable archive rather than a clear error.
#
# This grants the container no authority it did not already have -- it holds the
# database credentials either way. What it deliberately does NOT do is give the
# app the Docker socket to run `docker compose exec postgres pg_dump`, which
# would turn a web-app vulnerability into a host compromise.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
         -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt jammy-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-16 \
    && apt-get purge -y gnupg && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Never runs as root: a web-facing process has no reason to.
RUN groupadd --system inventory && useradd --system --gid inventory --home /app inventory
COPY --from=backend /build/backend/target/*.jar /app/inventory-manager.jar
USER inventory

EXPOSE 8080

# The app speaks plain HTTP on the internal Docker network only; TLS is
# terminated at the reverse proxy, which never routes here until this reports
# healthy -- and it will not report healthy until Flyway has finished.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD ["sh", "-c", "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/inventory-manager.jar"]
