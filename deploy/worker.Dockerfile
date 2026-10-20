# A worker: claims runs and executes them in sandbox containers through the host's Docker socket
# (it is as trusted as the Docker daemon; the sandboxes are the untrusted part).
#   docker build -f deploy/worker.Dockerfile -t factory-worker .
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY factory-worker/target/factory-worker-*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
