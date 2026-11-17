# A worker: claims runs and executes them in sandbox containers through the host's Docker socket
# (it is as trusted as the Docker daemon; the sandboxes are the untrusted part). Built from source:
#   docker build -f deploy/worker.Dockerfile -t factory-worker .
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY .mvn .mvn
COPY factory-contracts factory-contracts
COPY factory-worker factory-worker
COPY factory-backend/pom.xml factory-backend/pom.xml
RUN mvn --batch-mode --no-transfer-progress -pl factory-worker -am package -DskipTests

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /src/factory-worker/target/factory-worker-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
