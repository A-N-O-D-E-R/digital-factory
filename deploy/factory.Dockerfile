# The control plane with the built frontend, built from source (CI and local):
#   docker build -f deploy/factory.Dockerfile -t digital-factory .
FROM node:24-slim AS frontend
RUN corepack enable
WORKDIR /src/factory-frontend
COPY factory-frontend/package.json factory-frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY factory-frontend/ ./
RUN pnpm build

FROM maven:3-eclipse-temurin-25 AS backend
WORKDIR /src
COPY pom.xml ./
COPY .mvn .mvn
COPY factory-contracts factory-contracts
COPY factory-backend factory-backend
COPY factory-worker/pom.xml factory-worker/pom.xml
RUN mvn --batch-mode --no-transfer-progress -pl factory-backend -am package -DskipTests

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 factory
WORKDIR /app
COPY --from=backend /src/factory-backend/target/factory-backend-*.jar app.jar
COPY --from=frontend /src/factory-frontend/dist static
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/static/
USER factory
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
