# The sandbox image: the toolchains repositories build with, and the runner of agent runs.
# The worker starts it with no capabilities, a read-only root and its own uid; HOME is a tmpfs.
FROM node:24-bookworm-slim AS node

FROM eclipse-temurin:25-jdk-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends git maven ca-certificates ripgrep \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && npm install -g pnpm@10

WORKDIR /runner
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --prod --frozen-lockfile
COPY runner.ts ./
# git in the sandbox reads the worker's git directory, mounted read-only and owned by another user
RUN git config --system --add safe.directory '*'

WORKDIR /workspace
ENTRYPOINT ["node", "/runner/runner.ts"]
