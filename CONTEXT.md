# Glossaire — Digital Factory

| Terme | Définition |
|---|---|
| Work order | Unité de travail de l'usine, de l'entrée à la PR. Ne pas dire « task » ni « job ». |
| Ticket | Élément d'un outil externe d'où vient un work order : issue GitHub/GitLab, issue Jira, page ou ligne Notion. |
| Intake rule | Requête permanente (label, JQL, filtre de base Notion) qui transforme les nouveaux tickets en work orders. |
| Workflow | Définition versionnée du pipeline : graphe d'étapes, transitions conditionnelles, boucles bornées. Le pipeline classique n'est que le workflow par défaut. |
| Step | Étape d'un workflow : `agent` (un rôle d'agent), `factory` (action intégrée : tests, diff, push, PR, merge…) ou `gate` (décision humaine). Ne pas dire « phase ». |
| Condition | Prédicat d'un ensemble fermé (verdict, tests, risque, type, événement de PR…) qui choisit la transition suivante. Jamais de code libre. |
| Agent role | Type d'agent (classifier, analyst, specifier, implementer, reviewer, feedback-triage ou rôle créé) : prompt, outils, modèle. |
| Prompt version | Texte d'instructions d'un rôle, numéroté et immuable. |
| Gate | Step où le workflow attend un humain : validation du plan, approbation finale. |
| PR watching | Suivi d'une PR ouverte par l'usine jusqu'à sa fusion ou sa fermeture, avec réaction aux événements de la forge. |
| Feedback iteration | Boucle déclenchée par un retour de PR (revue, commentaire, CI rouge, conflit) : triage → code → preuves → push → réponse. |
| Trusted author | Auteur dont les commentaires de PR peuvent déclencher du travail (droit d'écriture sur le dépôt ou liste blanche). |
| Evidence | Fait établi par l'usine elle-même : diff, résultat des tests, résultat des tests d'acceptation. |
| Verdict | Avis d'un agent relecteur : PASS ou REVISE. |
| Settings | Paramètres en couches (défauts → global → dépôt), figés sur chaque work order à sa création. |
| Autonomy policy | Jusqu'où une classe de work order va sans humain : PR brouillon, PR prête, fusion. |
| Budget | Plafond de dépense en USD (par work order, par jour) ; l'atteindre met en pause. |
| Run | Une exécution d'un agent pour un stage d'un work order. |
| Worker / Drain | Hôte qui exécute les runs / ne plus lui donner de travail en laissant finir le sien. |
| Eval run / Fixture | Lot de work orders sur des dépôts fixtures, passé par le vrai pipeline et jugé par des tests cachés. |
| Candidate | Jeu de paramètres évalué contre les paramètres courants avant d'être promu. |
