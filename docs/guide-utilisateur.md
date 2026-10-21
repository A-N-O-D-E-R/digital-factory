# Guide d'utilisation — Digital Factory

Digital Factory est une usine logicielle autonome : elle prend une demande (un **work order**), la fait passer par
un **workflow** d'agents (classification, analyse, spécification, code, preuves, revue), ouvre une PR et la suit
jusqu'à sa fusion. Elle s'arrête aux **barrières** où un humain doit décider, et prouve elle-même ce qu'elle
affirme : diff, tests, tests d'acceptation, avis des relecteurs.

Ce guide suit l'interface page par page. Le vocabulaire (work order, step, gate, evidence…) est défini dans
[CONTEXT.md](../CONTEXT.md).

> Les captures viennent d'une usine locale (`dev/run-local.sh`) remplie de données de démonstration : dépôts,
> work orders et coûts sont fictifs.

## Sommaire

1. [Démarrer](#1-démarrer)
2. [Se connecter et rôles](#2-se-connecter-et-rôles)
3. [L'interface en un coup d'œil](#3-linterface-en-un-coup-dœil)
4. [Le tableau](#4-le-tableau)
5. [Créer un work order](#5-créer-un-work-order)
6. [Suivre un work order](#6-suivre-un-work-order)
7. [Approuver ou rejeter : les barrières](#7-approuver-ou-rejeter--les-barrières)
8. [Quand ça coince : bloqué, échoué, annulé](#8-quand-ça-coince--bloqué-échoué-annulé)
9. [Après la PR : la conversation de la PR](#9-après-la-pr--la-conversation-de-la-pr)
10. [Workflows](#10-workflows)
11. [Administration](#11-administration)
12. [Clavier, palette et thème](#12-clavier-palette-et-thème)
13. [Questions fréquentes](#13-questions-fréquentes)

---

## 1. Démarrer

### Essai local (sans fournisseur d'identité)

Prérequis : JDK 25, Maven, Node 22.22+ avec pnpm, Docker.

```sh
mvn package -DskipTests                       # depuis la racine du dépôt
(cd factory-frontend && pnpm install && pnpm build)
dev/run-local.sh                              # Postgres dans Docker + l'usine en connexion simulée
```

Puis ouvrir http://localhost:18080 (port modifiable avec `PORT=…`).

En local, aucun worker ni forge n'est branché : on peut naviguer, créer des dépôts et des work orders, éditer
workflows et paramètres, mais les runs restent en file d'attente tant qu'aucun worker n'est enregistré.

### Sur un hôte

```sh
docker build -f deploy/factory.Dockerfile -t digital-factory .
docker build -f deploy/worker.Dockerfile  -t factory-worker .
docker build -t factory-sandbox factory-sandbox
docker compose up -d
```

Variables principales (fichier `.env` à côté de `compose.yaml`) :

| Variable | Rôle |
|---|---|
| `FACTORY_DB_PASSWORD` | Mot de passe PostgreSQL (obligatoire). |
| `FACTORY_WORKER_JWT_SECRET` | Secret de signature des jetons des workers, 32 caractères ou plus (obligatoire). |
| `FACTORY_PUBLIC_URL` | URL publique de l'usine (liens dans les PR, retours de forge). |
| `FACTORY_OIDC_ENABLED` / `FACTORY_MOCK_LOGIN` | Connexion OIDC réelle, ou connexion simulée pour un essai. |
| `FACTORY_ADMIN_GROUPS`, `FACTORY_DEVELOPER_GROUPS`, `FACTORY_VIEWER_GROUPS` | Groupes OIDC donnant chaque rôle (défauts `factory-admins`, `factory-developers`, `factory-viewers`). |
| `FACTORY_WORKER_TOKEN` | Jeton du worker, obtenu dans *Administration › Workers* (voir [11.2](#112-workers)). |
| `ANTHROPIC_API_KEY` | Clé utilisée par les agents dans les sandbox. |
| `FACTORY_WORKER_SLOTS` | Nombre de runs simultanés par worker (défaut 2). |

Forges et sources de tickets se configurent dans `factory.env` avec les variables des starters
(`ANODE_GITHUB_APPID`, `ANODE_GITHUB_PRIVATEKEY`, `ANODE_GITLAB_TOKEN`, `ANODE_JIRA_*`, `ANODE_NOTION_TOKEN`…) :
ne renseigner que celles utilisées.

Ordre de mise en route conseillé :

1. Démarrer l'usine, se connecter en administrateur.
2. Vérifier les intégrations ([11.1](#111-intégrations)).
3. Enregistrer un worker, copier son jeton dans `FACTORY_WORKER_TOKEN`, redémarrer le worker ([11.2](#112-workers)).
4. Enregistrer les dépôts ([11.4](#114-dépôts)).
5. Fixer les budgets ([11.5](#115-usine--pause-et-budgets)).
6. Créer un premier work order ([5](#5-créer-un-work-order)) ou une règle d'import de tickets ([11.8](#118-tickets--import-et-règles)).

---

## 2. Se connecter et rôles

![Page de connexion](images/01-connexion.png)

Sans session, l'application renvoie vers la page de connexion. En connexion simulée (`dev/run-local.sh`), trois
utilisateurs existent, mot de passe quelconque :

| Utilisateur | Rôle | Peut |
|---|---|---|
| `ada` | Administrateur | Tout : work orders, approbations, administration, paramètres. |
| `dev` | Développeur | Créer/annuler/reprendre des work orders, décider aux barrières, lire les paramètres. |
| `vic` | Lecteur | Tout voir, ne rien changer. |

L'interface s'adapte au rôle : un lecteur ne voit ni « Nouveau work order » ni les boutons d'approbation, et
l'administration lui est fermée.

![Un lecteur sur une page d'administration](images/30-lecteur-refuse.png)

---

## 3. L'interface en un coup d'œil

![Le tableau, vue d'ensemble](images/02-tableau.png)

- **Barre latérale, en haut** : *Tableau*, *Approbations* (avec le nombre de barrières en attente), *Workflows*.
- **Barre latérale, au milieu** : tous les work orders, ceux qui vous attendent en premier, puis du plus récent
  au plus ancien. La pastille donne l'état :

  | Pastille | État | Signifie |
  |---|---|---|
  | jaune | Vous attend | Une barrière attend une décision humaine. |
  | bleue | Au travail | Une étape s'exécute (run en file ou en cours). |
  | orange | Bloqué | L'usine ne peut pas avancer seule ; il faut des instructions. |
  | rouge | Échoué | Abandonné après les essais permis. |
  | verte | Terminé | Fini ou PR fusionnée. |
  | grise | Inactif | En pause (budget, usine), annulé, ou PR fermée. |

- **Barre latérale, en bas** : roue dentée vers l'administration (administrateurs), nom de l'utilisateur, rappel
  `Ctrl+K`, bascule de thème.
- **Notifications de barrière** : quand un work order arrive à une barrière, une notification apparaît en bas à
  gauche ; un clic ouvre le work order.

![Notifications de barrières en attente](images/32-notifications.png)

Tout est **en direct** : l'interface reçoit les événements de l'usine et se met à jour sans recharger.

---

## 4. Le tableau

Le tableau range les work orders en six colonnes, une par état (voir le tableau des pastilles ci-dessus).
Chaque carte donne le titre, le dépôt, l'étape en cours et la dernière activité. On voit d'un coup d'œil où le
flux s'accumule : beaucoup de cartes « Vous attend » signifient que les humains sont le goulot.

Le bouton **Par étape** regroupe plutôt les work orders en cours par étape du workflow (`implement`,
`final-gate`…) : où ils s'empilent dans le pipeline.

![Tableau par étape](images/03-tableau-par-etape.png)

---

## 5. Créer un work order

Deux façons, réservées aux développeurs et administrateurs.

### Saisie directe

**Nouveau work order** (ou la touche `n`) ouvre le formulaire :

- **Dépôt** : parmi les dépôts actifs ([11.4](#114-dépôts)).
- **Titre** : court, il sert partout (barre latérale, PR).
- **Description** : la demande. Plus elle est précise (comportement attendu, bornes, exemples), meilleurs sont le
  plan et les critères d'acceptation. Pour un bug, donner un moyen de le reproduire : un bug non reproduit
  bloque le work order à l'analyse.

![Formulaire de nouveau work order](images/04-nouveau-work-order.png)

**Créer** ouvre directement la page du work order.

### Import d'un ticket

**Importer un ticket** accepte une clé ou une URL : `OPS-12` (Jira), `github:acme/api#12`, ou l'URL d'une issue
GitHub/GitLab, d'une page Notion. Le ticket devient un work order sur le dépôt choisi ; « Dépôt du ticket »
convient aux issues GitHub/GitLab, un ticket Jira ou Notion demande de choisir le dépôt. Une réimportation va
sur le dépôt de l'import précédent. Pour importer
automatiquement, voir les règles d'import ([11.8](#118-tickets--import-et-règles)).

![Import d'un ticket](images/05-importer-ticket.png)

Les paramètres (modèles, budgets, seuils) sont **figés à la création** du work order : changer les paramètres
ensuite ne modifie pas les work orders déjà lancés.

---

## 6. Suivre un work order

![Haut de la page d'un work order](images/08-work-order-haut.png)

L'en-tête donne le titre, l'état, l'étape et son passage (une boucle repasse par la même étape : `implement`
passage 2…), le dépôt, le workflow et sa version, et **le coût cumulé**. Le bouton **Annuler** arrête un work
order en cours.

La page se lit de haut en bas :

| Section | Contenu |
|---|---|
| Barrière | Si une décision vous attend : commentaire, **Approuver**, **Rejeter** ([7](#7-approuver-ou-rejeter--les-barrières)). |
| Demande | La description d'origine. |
| Plan | Le plan de l'analyste et ses critères d'acceptation. |
| Preuves | Ce que l'usine a établi elle-même : diff, tests, avis des relecteurs. |
| Runs | Chaque exécution d'agent ou d'action, avec modèle, jetons, coût, journal. |
| Faits | Les faits connus du workflow (type, risque, tests passés…) qui choisissent les transitions. |
| Parcours | Les étapes traversées, dans l'ordre. |
| Conversation de la PR | Retours de la forge, une fois la PR ouverte ([9](#9-après-la-pr--la-conversation-de-la-pr)). |
| Chronologie | Tous les événements horodatés. |

[Page complète d'un work order](images/09-work-order-complet.png)

### Preuves

![Preuves : diff, tests, verdicts](images/10-preuves.png)

- **Diff** : nombre de fichiers, lignes ajoutées et retirées, puis le diff côte à côte, fichier par fichier
  (repliable).
- **Tests** et **Acceptation** : réussis / total, ignorés, et le nom des tests en échec. Les tests d'acceptation
  sont écrits par le *specifier* à partir des critères du plan.
- **Verdicts** : chaque relecteur (`reviewer`, `security-reviewer`, rôles de revue ajoutés) donne *Validé* ou
  *À revoir*, un résumé et ses commentaires par fichier et ligne. Un *À revoir* renvoie le travail à
  l'implémentation, un nombre borné de fois.

Ces preuves ne viennent pas des agents : l'usine relance elle-même les tests dans une sandbox propre.

### Runs et journaux

Un clic sur une ligne de run affiche l'erreur éventuelle et la fin de ce que la sandbox a imprimé.

![Table des runs, journal d'un run ouvert](images/11-journal-run.png)

Colonnes : étape et passage, tâche (rôle d'agent ou action), essai (un run échoué peut être retenté), statut,
modèle, jetons entrée → sortie, coût, heure de fin. Le journal est un texte non fiable affiché tel quel.

### Faits, parcours, chronologie

![Faits](images/11d-faits.png)

![Parcours](images/11b-parcours.png)

![Chronologie](images/11c-chronologie.png)

Les faits expliquent pourquoi le workflow a pris une transition (par exemple `risk = HIGH` déclenche la barrière
du plan) ; le parcours et la chronologie expliquent comment le work order en est arrivé là.

---

## 7. Approuver ou rejeter : les barrières

Une **barrière** est une étape où le workflow attend un humain. Le workflow par défaut en a deux :

| Barrière | Quand | Sur quoi décider |
|---|---|---|
| **Plan** (`plan-gate`) | Seulement si le risque est `HIGH` (sécurité, montée majeure de dépendance, gros changement…). | Le plan et les critères d'acceptation, avant qu'une ligne soit écrite. |
| **PR finale** (`final-gate`) | Quand la politique d'autonomie de la classe n'autorise pas l'usine à aller plus loin seule ([11.3](#113-autonomie)). | La PR brouillon : diff, tests, verdicts. Approuver la passe en « prête ». |

La page **Approbations** regroupe tout ce qui attend une décision, le plus ancien d'abord, avec à gauche ce sur
quoi on décide (plan, preuves, diff) et à droite la décision.

![Page Approbations](images/12-approbations.png)

La même décision se prend depuis la page du work order :

![Barrière du plan sur un work order à risque élevé](images/13-barriere-plan.png)

- **Approuver** : le workflow reprend à l'étape suivante.
- **Rejeter** : le work order est annulé.
- Le **commentaire** (facultatif) est gardé dans la chronologie.

Il faut le rôle développeur ou administrateur.

---

## 8. Quand ça coince : bloqué, échoué, annulé

### Bloqué

L'usine s'arrête d'elle-même quand continuer serait deviner : demande jugée invalide, bug non reproduit, diff vide,
boucle de révision épuisée, secret détecté à répétition… La raison est sous l'en-tête.

![Work order bloqué, formulaire de reprise](images/14-bloque-reprise.png)

Pour repartir : écrire dans **Instructions de reprise** ce qui manquait (le scénario du bug, une précision sur le
périmètre…), puis **Reprendre**. Le work order repart de la dernière étape qu'il a exécutée, avec ces instructions
(signées de votre nom).

### Échoué

Un work order échoue quand ses essais sont épuisés (tests toujours rouges, runs en échec, budget de run dépassé).
La raison, les tests en échec et l'erreur du dernier run sont affichés ; ouvrir le journal du run pour le
détail.

![Work order échoué](images/15-echoue.png)

Un work order échoué ne se reprend pas : en créer un nouveau, avec une description qui lève la cause.

### Annulé, en pause

- **Annuler** arrête un work order à tout moment ; un rejet à une barrière l'annule aussi.
- Un work order est **en pause** quand un budget est atteint ou que l'usine est en pause ([11.5](#115-usine--pause-et-budgets)) :
  il repart seul quand la limite est levée. Il n'échoue jamais pour cause de budget.

---

## 9. Après la PR : la conversation de la PR

Une fois la PR ouverte, l'usine la **surveille** jusqu'à sa fusion ou sa fermeture et réagit aux événements de la
forge :

- commentaire ou revue demandant un changement → triage, code, preuves, push, réponse sur la PR ;
- CI rouge → correction ;
- branche en retard ou en conflit → mise à jour de la branche ;
- fusion → work order *Fusionné* ; fermeture → *PR fermée*.

![Conversation de la PR](images/16-conversation-pr.png)

Seuls les **auteurs de confiance** (droit d'écriture sur le dépôt ou liste blanche) déclenchent du travail. Les
autres messages sont affichés, grisés, avec la raison (« ignoré : auteur non fiable »). Les fils de revue que
l'usine a traités sont marqués résolus sur la forge.

---

## 10. Workflows

Un workflow est le graphe d'étapes que suit un work order. Le workflow `default` : classifier → analyser →
(barrière du plan si risque élevé) → spécifier → implémenter → tests → recherche de secrets → revue → push → PR
brouillon → (barrière finale) → PR prête → (fusion si autorisée) → surveillance de la PR.

![Liste des workflows](images/17-workflows.png)

La page d'un workflow montre à gauche ses étapes et transitions en clair, à droite sa définition JSON.

![Éditeur de workflow](images/18-workflow-editeur.png)

Chaque étape est d'un type :

- `agent` : un ou plusieurs rôles d'agent (classifier, analyst, implementer, reviewer…) ;
- `factory` : une action intégrée (`run-tests`, `secret-scan`, `push-branch`, `open-draft-pr`, `merge`, `watch-pr`…) ;
- `gate` : une décision humaine (`plan`, `final`).

Les transitions portent des **conditions** sur les faits (`tests.passed = false`, `verdict = REVISE`,
`risk = HIGH`, `pr.event = merged`…), jamais du code libre. Les boucles sont **bornées** (« au plus 3×, puis
@blocked »). Les destinations `@blocked`, `@cancelled`, `@done` terminent le work order.

Pour modifier un workflow (administrateurs) :

1. Choisir la version de départ dans la liste déroulante (les versions sont immuables).
2. Éditer le JSON.
3. **Valider** : vérifie la structure, les rôles et actions, que chaque boucle est bornée.

   ![Workflow valide](images/19-workflow-valide.png)

4. **Simuler** : rejoue le workflow à blanc. Le champ « Ce que chaque passage apprend » donne, par étape, les
   faits que chaque passage établit ; la simulation affiche le chemin pris et pourquoi. Dans l'exemple, la
   surveillance de PR ne reçoit jamais d'événement et finit `@blocked` après 20 passages : la borne fonctionne.

   ![Simulation d'un workflow](images/20-workflow-simulation.png)

5. **Publier** avec une note de version. Les nouveaux work orders prennent la dernière version ; ceux en cours
   gardent la leur.

---

## 11. Administration

Réservée aux administrateurs : roue dentée en bas de la barre latérale, ou `g` puis `i`. Les onglets :

### 11.1 Intégrations

État de chaque intégration (GitHub, GitLab, Jira, Notion) : non configurée (gris), connectée (vert, avec le
détail), en échec (rouge, avec l'erreur). Les identifiants ne sont jamais affichés, seulement s'ils marchent.
**Vérifier à nouveau** relance les contrôles.

![Intégrations](images/21-admin-integrations.png)

### 11.2 Workers

Les hôtes qui exécutent les runs dans des sandbox.

![Workers](images/22-admin-workers.png)

- **Enregistrer** un worker par son nom : son jeton s'affiche **une seule fois**, à copier dans
  `FACTORY_WORKER_TOKEN` du worker.
- Le tableau donne version, image, runs occupés / emplacements, dernier signe de vie, état.
- **Vider** : le worker ne reçoit plus de travail mais finit le sien (pour le mettre à jour sans tuer de run) ;
  **Reprendre** annule la vidange.
- **Révoquer** : son jeton cesse de fonctionner.

### 11.3 Autonomie

Jusqu'où l'usine va seule, par classe de work order (`BUG`, `FEATURE`, `DEPENDENCY`, `SECURITY`…) :

- **PR brouillon** : l'usine s'arrête à la barrière finale, un humain approuve.
- **Fusion** : l'usine fusionne elle-même quand les vérifications passent.

![Autonomie](images/23-admin-autonomie.png)

Le niveau *Fusion* ne suffit pas : la fusion se **gagne**. L'usine ne fusionne que si le taux de succès mesuré par
les évaluations complètes ([11.9](#119-évaluations)) atteint le taux requis, que le work order n'est pas à risque
`HIGH`, que toutes ses preuves sont vertes (tests, acceptation, revue, recherche de secrets) et que les
vérifications de la forge passent. Elle se **reprend** : si le taux d'échecs réels dépasse le maximum, la classe
redescend d'elle-même en *PR brouillon*. La colonne *Par* indique qui a changé le niveau (`factory: …` quand
l'usine l'a repris).

### 11.4 Dépôts

Les dépôts sur lesquels l'usine travaille.

![Dépôts](images/24-admin-depots.png)

| Champ | Exemple | Rôle |
|---|---|---|
| Forge | `GITHUB`, `GITLAB` | Où vit le dépôt. |
| Chemin | `acme/api` | Le dépôt sur la forge ; sa clé devient `github:acme/api`. |
| Branche par défaut | `main` | Base des branches de travail et cible des PR. |
| Commande de test | `mvn -B verify` | Lancée par l'usine pour produire les preuves. |
| Rapports JUnit | `**/target/surefire-reports/*.xml` | Où lire les résultats des tests. |
| Image sandbox | `factory-sandbox` | L'image où tournent agents et tests, construite sur `factory-sandbox` avec les outils du dépôt. |

**Désactiver** retire un dépôt du formulaire de création sans toucher aux work orders existants.

### 11.5 Usine : pause et budgets

![Usine : pause et budgets](images/25-admin-usine.png)

- **Mettre en pause** : plus aucun run n'est distribué ; les runs en cours finissent. **Reprendre** relance.
  Aussi disponible depuis la palette de commandes.
- **Budget quotidien** et **budget par work order** (USD, vide = illimité). Un budget atteint met les work orders
  en pause, il ne les fait pas échouer.
- La dépense du jour est rappelée.

### 11.6 Paramètres

Paramètres en couches : défauts → global → dépôt. La portée (*Global* ou un dépôt) se choisit en haut.

![Paramètres](images/26-admin-parametres.png)

- À gauche, la couche éditée : un JSON Merge Patch, ne contenant que ce qui diffère de la couche du dessous.
- À droite, les **paramètres effectifs** après fusion : modèles, et par rôle `model`, `maxTurns`, `effort`,
  `timeoutMinutes`, `budgetUsd`, `cpus`, `memoryMb` ; seuils de risque (`risk.highAboveFiles`)…
- Chaque enregistrement exige une **raison** ; l'**historique** garde chaque changement, et **Restaurer l'état
  d'avant** l'annule.

### 11.7 Rôles et prompts

![Rôles et prompts](images/27-admin-roles.png)

- À gauche, les rôles d'agent avec leur dernière version de prompt.
- Les versions de prompt sont **immuables et numérotées** ; la liste déroulante affiche une version et son diff
  ligne à ligne avec la précédente.
- Modifier le texte, décrire le changement, **Publier une version**.
- **Nouveau rôle de revue** : ajoute un relecteur spécialisé (par exemple `perf-reviewer`) utilisable dans une
  étape de revue d'un workflow.

Avant de promouvoir un changement de prompt ou de modèle partout, l'évaluer ([11.9](#119-évaluations)).

### 11.8 Tickets : import et règles

![Tickets](images/28-admin-tickets.png)

- **Importer** un ticket à la main (comme depuis le tableau).
- **Règles** : une requête permanente qui transforme les nouveaux tickets en work orders.

  | Champ | Exemple |
  |---|---|
  | Nom | `shop-factory` |
  | Source | `github`, `gitlab`, `jira`, `notion` |
  | Projet | `acme/api`, `OPS`, id de base Notion |
  | Filtre | un label (`factory`), une JQL, `Statut = Prêt` |
  | Dépôt cible | `github:acme/api` |
  | Workflow | `default` si vide |
  | Lus au plus / Créés au plus par passe | garde-fous contre un filtre trop large |

  Chaque règle active est passée **automatiquement toutes les 5 minutes** (propriété `factory.intake.interval`,
  par exemple `FACTORY_INTAKE_INTERVAL=1m`) ; un ticket n'est importé qu'une fois. **Lancer une passe** exécute la
  règle tout de suite ; la colonne *Dernière passe* donne l'heure ou l'erreur
  (intégration non configurée, 401…). **Désactiver** suspend une règle.
- **Tickets importés** : le lien vers le ticket et son work order. **Réimporter** crée un nouveau work order pour
  le même ticket.

### 11.9 Évaluations

Les évaluations passent des work orders sur des dépôts fixtures par le vrai pipeline, puis les jugent.

![Évaluations](images/29-admin-evaluations.png)

- **Rapide** : classification et analyse seulement, pour comparer vite un prompt ou un modèle.
- **Complète** : tout le workflow, jugé par des tests cachés. Son taux de succès par classe alimente la politique
  d'autonomie.
- **Candidat** (facultatif) : un jeu de paramètres à essayer, en JSON, par exemple
  `{"roles":{"implementer":{"effort":"max"}}}`.
- **Référence** sur une évaluation passée compare chaque fixture : ↑ gagnée, ↓ perdue.
- **Promouvoir** copie le candidat dans les paramètres globaux.

Les runs d'une évaluation sont facturés et comptent dans les budgets ; une confirmation est demandée.

---

## 12. Clavier, palette et thème

**`Ctrl+K`** (ou `⌘K`) ouvre la palette : taper pour trouver un work order (titre, dépôt), une page, un dépôt, ou
une action (nouveau work order, importer un ticket, mettre l'usine en pause).

![Palette de commandes](images/06-palette.png)

**`?`** affiche les raccourcis :

| Touches | Action |
|---|---|
| `Ctrl+K` | Palette de commandes |
| `g` puis `t` | Tableau |
| `g` puis `a` | Approbations |
| `g` puis `w` | Workflows |
| `g` puis `i` | Administration |
| `n` | Nouveau work order |
| `?` | Aide des raccourcis |

![Aide des raccourcis](images/07-raccourcis.png)

L'icône en bas de la barre latérale passe du thème du système au thème clair, puis sombre.

![Thème sombre](images/31-theme-sombre.png)

---

## 13. Questions fréquentes

**Mon work order reste « Au travail » sans bouger.** Aucun worker ne prend ses runs : vérifier
*Administration › Workers* (signe de vie récent, pas en vidange), que l'usine n'est pas en pause et que le budget
du jour n'est pas atteint.

**Pourquoi une barrière du plan sur ce work order et pas sur l'autre ?** Elle n'apparaît que si le risque est
`HIGH` : voir les faits `type` et `risk`. Les classes `SECURITY`, les montées majeures de dépendance et les diffs
de plus de `risk.highAboveFiles` fichiers sont toujours à risque élevé.

**L'usine a ignoré un commentaire sur la PR.** Son auteur n'est pas de confiance : la conversation de la PR
l'affiche grisé, avec la raison.

**J'ai changé un paramètre, mes work orders en cours ne le prennent pas.** Normal : les paramètres sont figés à
la création. Seuls les nouveaux work orders prennent le changement.

**Comment laisser l'usine fusionner seule ?** Passer la classe en *Fusion* dans *Autonomie*, après des
évaluations complètes dont le taux de succès atteint le seuil requis.

**Un bug est bloqué « non reproduit ».** Donner dans les instructions de reprise un scénario précis (étapes,
données, environnement), puis *Reprendre*.
