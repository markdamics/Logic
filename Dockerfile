# syntax=docker/dockerfile:1

# ---- Frontend build ----
FROM node:24-slim AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- Backend build ----
FROM maven:3.9-eclipse-temurin-25 AS backend-build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
# Bundle the built frontend as Spring Boot static resources, so the one jar
# serves both the API (/api/**) and the UI (/) from the same origin - no
# CORS, no second container, no reverse proxy needed for a normal deployment.
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

# ---- Runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=backend-build /app/backend/target/log-analyzer-backend-*.jar app.jar

# Durable state (H2 database, encryption key, Lucene search index) lives
# under ./data relative to the working directory - mount a volume here.
VOLUME ["/app/data"]

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
