# 1. Adopt the A-N-O-D-E-R baseline

Date: 2026-11-17 — Status: accepted

## Context

The four A-N-O-D-E-R apps (idea-generator, ai-scientist, productDesigner, digital-factory) each did auth, Jira,
LLM access, build and frontend tooling their own way. The factory was the only one on the starter collection's
BOM, and on a `0.1.0-SNAPSHOT` of it, so its backend and worker CI could not run on a clean machine.

## Decision

Align on one baseline built on `springboot-starter-collection`:

- `io.github.a-n-o-d-e-r` coordinates and `com.anode.<app>` packages (already the case here);
- `spring-boot-starter-parent` 4.1.2, Java 25;
- import `springboot-starter-collection-dependencies` at a released version (0.2.0), never a SNAPSHOT;
- CI is the collection's reusable workflow (`reusable-ci.yml`), updates come from its Renovate preset;
- the glossary is `GLOSSARY.md` (was `CONTEXT.md`), decisions live in `docs/adr/`.

The factory's own version follows the collection's (0.2.0). The Claude agent SDK in `factory-sandbox` stays: it is the
agent runtime, not a chat-completion client.

## Consequences

- Backend and worker CI run again (Testcontainers needs Docker, available on GitHub runners).
- A collection release is a prerequisite for a green CI here.
- Renovate may now propose `io.github.a-n-o-d-e-r:` updates, grouped with the Spring Boot parent.
