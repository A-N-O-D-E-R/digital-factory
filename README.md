# Digital Factory

Usine logicielle autonome : du ticket à la PR, avec preuves produites par l'usine et autonomie gagnée par évaluation.

- Spec : voir l'issue « Spec: Digital Factory v1 » de ce dépôt.
- Vocabulaire : [CONTEXT.md](CONTEXT.md).

## Modules

- `factory-backend` — plan de contrôle (Spring Boot 4.1, Java 25) ; sert aussi l'interface.
- `factory-worker` — réclame les runs et les exécute dans des conteneurs sandbox.
- `factory-sandbox` — image des sandbox et runner des agents (Claude Agent SDK) ; `egress/` : le proxy de sortie.
- `factory-frontend` — interface (React 19, Vite, TanStack) ; pnpm, Node 22.22+.
- `factory-contracts` — schémas JSON (sorties d'agents, workflows, paramètres).

## Lancer

Construire (JDK 25, depuis la racine) : `mvn package -DskipTests`, puis `cd factory-frontend && pnpm install && pnpm build`.

- **Essai local, sans fournisseur d'identité** : `dev/run-local.sh` (Postgres dans Docker, connexion simulée :
  `ada` admin, `dev` développeur, `vic` lecteur, mot de passe quelconque), puis http://localhost:18080.
  Test navigateur : `cd factory-frontend && pnpm e2e`.
- **Sur un hôte** : `docker build -f deploy/factory.Dockerfile -t digital-factory .`,
  `docker build -f deploy/worker.Dockerfile -t factory-worker .`, `docker build -t factory-sandbox factory-sandbox`,
  puis `docker compose up -d` (variables dans `compose.yaml`). Le dépôt cible indique son image sandbox
  (`sandboxImage`), construite sur `factory-sandbox`.

## Tests

- `mvn test` (Docker requis : PostgreSQL et sandbox réels).
- `cd factory-frontend && pnpm test` ; `pnpm e2e` contre `dev/run-local.sh`.
- `cd factory-sandbox && pnpm test` ; `factory-sandbox/egress/check.sh` vérifie le proxy de sortie.
- Qualité : Error Prone à chaque compilation (`.mvn/jvm.config`), couverture JaCoCo (`target/site/jacoco/`),
  oxlint pour le frontend, Renovate pour les dépendances ; CI GitHub Actions pour le frontend et le sandbox
  (le backend et le worker attendent la publication des starters).

