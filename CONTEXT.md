# Glossaire — Digital Factory

| Terme | Définition |
|---|---|
| Work order | Unité de travail de l'usine, de l'entrée à la PR. Ne pas dire « task » ni « job ». |
| Ticket | Élément d'un outil externe d'où vient un work order : issue GitHub/GitLab, issue Jira, page ou ligne Notion. |
| Intake rule | Requête permanente (label, JQL, filtre de base Notion) qui transforme les nouveaux tickets en work orders. |
| Stage | Étape du pipeline : classify, analyze, specify, implement, review. Ne pas dire « phase ». |
| Agent role | Type d'agent (classifier, analyst, specifier, implementer, reviewer ou rôle de revue créé) : prompt, outils, modèle. |
| Prompt version | Texte d'instructions d'un rôle, numéroté et immuable. |
| Workflow template | Agencement versionné des stages, choisi par type de work order. |
| Gate | Point où le pipeline attend un humain : validation du plan, approbation finale. |
| Evidence | Fait établi par l'usine elle-même : diff, résultat des tests, résultat des tests d'acceptation. |
| Verdict | Avis d'un agent relecteur : PASS ou REVISE. |
| Settings | Paramètres en couches (défauts → global → dépôt), figés sur chaque work order à sa création. |
| Autonomy policy | Jusqu'où une classe de work order va sans humain : PR brouillon, PR prête, fusion. |
| Budget | Plafond de dépense en USD (par work order, par jour) ; l'atteindre met en pause. |
| Run | Une exécution d'un agent pour un stage d'un work order. |
| Worker / Drain | Hôte qui exécute les runs / ne plus lui donner de travail en laissant finir le sien. |
| Eval run / Fixture | Lot de work orders sur des dépôts fixtures, passé par le vrai pipeline et jugé par des tests cachés. |
| Candidate | Jeu de paramètres évalué contre les paramètres courants avant d'être promu. |
