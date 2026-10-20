# The control plane with the built frontend. Built from what the host built (the starters are local
# snapshots): mvn -q package -DskipTests && (cd factory-frontend && pnpm build), then from the root:
#   docker build -f deploy/factory.Dockerfile -t digital-factory .
FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 factory
WORKDIR /app
COPY factory-backend/target/factory-backend-*-SNAPSHOT.jar app.jar
COPY factory-frontend/dist static
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/static/
USER factory
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
