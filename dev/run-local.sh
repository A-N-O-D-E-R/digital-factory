#!/bin/sh
# The factory on this machine, no identity provider: Postgres in Docker, the backend in mock login
# (users ada = admin, dev = developer, vic = viewer, any password) serving the built frontend.
#   build first: mvn -q package -DskipTests -pl factory-backend -am && (cd factory-frontend && pnpm build)
#   then: dev/run-local.sh  ->  http://localhost:${PORT:-18080}
set -eu
cd "$(dirname "$0")/.."
PORT=${PORT:-18080}
if ! docker inspect factory-local-db >/dev/null 2>&1; then
  docker run -d --name factory-local-db -e POSTGRES_USER=factory -e POSTGRES_PASSWORD=factory -e POSTGRES_DB=factory \
    -p 55432:5432 postgres:17-alpine >/dev/null
fi
docker start factory-local-db >/dev/null
until docker exec factory-local-db pg_isready -U factory >/dev/null 2>&1; do sleep 1; done
FACTORY_DB_URL=jdbc:postgresql://localhost:55432/factory FACTORY_DB_PASSWORD=factory \
FACTORY_OIDC_ENABLED=false ANODE_SECURITY_MOCK=true \
FACTORY_WORKER_JWT_SECRET=${FACTORY_WORKER_JWT_SECRET:-local-only-secret-0123456789abcdef0123} \
FACTORY_PUBLIC_URL=http://localhost:$PORT SERVER_PORT=$PORT \
SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:factory-frontend/dist/ \
  exec java -jar factory-backend/target/factory-backend-0.1.0-SNAPSHOT.jar
