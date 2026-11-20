# LAB — Construire un pipeline Jenkins pour une API Spring Boot

Formation CI/CD — Soufiane SEJJARI

Vous connaissez Git, les concepts CI/CD, GitHub Actions, Docker, les environnements
dev / staging / prod, les approbations et le rollback. Aujourd'hui, vous mettez tout cela
en pratique avec **Jenkins**, sur une vraie API Java.

Chaque étape contient :

- 🎯 **Objectif** — ce que vous allez faire
- **Commandes** — à taper dans un terminal
- ✅ **Point de contrôle** — ce que vous devez voir avant de passer à la suite
- 💡 **À retenir** — le lien avec le cours

> Conventions : les commandes sont lancées depuis le dossier `lab-jenkins-springboot/`
> sauf mention contraire. Sous Windows, utilisez Git Bash ou WSL (et `mvnw.cmd` hors WSL).
> L'interface Jenkins s'affiche dans la langue de votre navigateur : les libellés anglais
> sont donnés entre parenthèses.

---

## Étape 0 — Prérequis, dépôt Git

🎯 **Objectif** : vérifier vos outils et transformer le dossier du lab en dépôt Git.

```bash
git --version
java -version          # 21 ou plus récent
docker info            # doit répondre en quelques secondes
docker compose version
```

Jenkins ne lit pas vos fichiers : il lit les **commits** d'un dépôt Git. Deux options.

**Option A — hors-ligne (recommandée en salle)** : le dossier du lab est monté dans Jenkins
en lecture seule (`/repo`) et le job lit `file:///repo`.

```bash
cd lab-jenkins-springboot
git init -b main
git add -A
git commit -m "Lab de départ"
git log --oneline
```

**Option B — GitHub** : créez un dépôt **public** sur votre compte (ou forkez celui du formateur)
et poussez-y le lab.

```bash
git remote add origin https://github.com/<votre-compte>/lab-jenkins-springboot.git
git push -u origin main
```

✅ **Point de contrôle** : `git log --oneline` affiche un commit sur la branche `main`.

💡 **À retenir** : la CI part toujours d'une **version identifiée** du code (un commit).
« Ça marche sur ma machine » ne compte pas : seul ce qui est commité est construit.

---

## Étape 1 — Découvrir et lancer l'API en local

🎯 **Objectif** : comprendre l'application que le pipeline va livrer.

Parcourez le code (`src/main/java/fr/formation/tasks`) :

| Couche | Classe | Rôle |
|---|---|---|
| API | `TaskController` | endpoints REST `/api/tasks` |
| API | `TaskRequest`, `TaskResponse`, `ApiError` | DTO (records Java) |
| API | `ApiExceptionHandler` | `@RestControllerAdvice` : erreurs 400 / 404 en JSON propre |
| Service | `TaskService` | règles métier (titre nettoyé, non vide, 120 caractères max) |
| Repository | `TaskRepository` | Spring Data JPA |
| Base | `db/migration/V1__create_task.sql` | schéma géré par Flyway (H2 en local, PostgreSQL en staging/prod) |

```bash
./mvnw spring-boot:run
```

Dans un second terminal :

```bash
# Créer une tâche (notez les espaces autour du titre)
curl -i -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"  Installer Jenkins  "}'

# Lister / lire / modifier / supprimer
curl http://localhost:8080/api/tasks
curl http://localhost:8080/api/tasks/1
curl -X PUT http://localhost:8080/api/tasks/1 -H 'Content-Type: application/json' \
  -d '{"title":"Installer Jenkins","done":true}'
curl -i -X DELETE http://localhost:8080/api/tasks/1

# Cas d'erreur
curl -i http://localhost:8080/api/tasks/999                      # 404
curl -i -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' -d '{"title":""}'          # 400

# Supervision
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info
```

Arrêtez l'application avec `Ctrl+C`.

✅ **Point de contrôle** :
- le `POST` renvoie `HTTP/1.1 201`, un en-tête `Location: http://localhost:8080/api/tasks/1`
  et un titre **sans espaces** : `"title":"Installer Jenkins"` ;
- le titre vide renvoie `400` avec `"fieldErrors":{"title":"le titre est obligatoire"}` ;
- `/actuator/health` renvoie `{"status":"UP",...}` ;
- `/actuator/info` contient `"env":"local"`, la version Maven (`build`) et le commit Git (`git`).

💡 **À retenir** : `/actuator/health` servira aux **smoke tests** du pipeline, et
`/actuator/info` à vérifier **quelle version** tourne dans chaque environnement (traçabilité).

---

## Étape 2 — Lancer les tests et lire le rapport de couverture

🎯 **Objectif** : exécuter localement exactement ce que la CI exécutera.

```bash
./mvnw -B verify
```

`verify` enchaîne : **Checkstyle** (phase `validate`) → compilation → **tests** →
jar → rapport **JaCoCo** → contrôle de couverture (**≥ 70 %** des lignes).

Les trois niveaux de tests :

| Test | Type | Ce qu'il vérifie |
|---|---|---|
| `TaskServiceTest` | unitaire (JUnit 5 + Mockito) | règles métier, sans Spring ni base |
| `TaskControllerTest` | `@WebMvcTest` | couche HTTP seule : statuts, JSON, validation 400, 404 |
| `TasksApiIntegrationTest` | `@SpringBootTest` + MockMvc + H2 | application complète, Flyway compris |

Ouvrez le rapport de couverture :

```bash
open target/site/jacoco/index.html        # macOS
xdg-open target/site/jacoco/index.html    # Linux
```

✅ **Point de contrôle** :
- `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` puis `BUILD SUCCESS` ;
- `You have 0 Checkstyle violations.` et `All coverage checks have been met.` ;
- le rapport JaCoCo affiche une couverture de lignes d'environ **97 %**.

💡 **À retenir** : le pipeline ne doit contenir **aucune magie** : chaque stage doit pouvoir
être rejoué en local avec la même commande. C'est ce qui rend un échec de CI diagnosticable.

---

## Étape 3 — Démarrer Jenkins

🎯 **Objectif** : lancer un Jenkins local entièrement configuré par du code (JCasC).

```bash
cd jenkins
docker compose up -d --build     # 1er lancement : 5 à 10 minutes (image + plugins)
docker compose logs -f jenkins   # attendre « Jenkins is fully up and running », puis Ctrl+C
cd ..
```

Pour l'option B (GitHub), lancez plutôt :

```bash
cd jenkins
TASKS_REPO_URL=https://github.com/<votre-compte>/lab-jenkins-springboot.git docker compose up -d --build
```

Ouvrez http://localhost:8090 et connectez-vous avec **admin / admin** (lab local uniquement !).

Regardez les fichiers qui ont produit ce Jenkins :

| Fichier | Rôle |
|---|---|
| `jenkins/Dockerfile` | image officielle `jenkins/jenkins:lts-jdk21` + CLI Docker + plugins |
| `jenkins/plugins.txt` | liste des plugins (pipeline, git, junit, coverage, JCasC, job-dsl...) |
| `jenkins/casc.yaml` | utilisateur admin, sécurité, et **création du job `tasks-api`** |
| `jenkins/compose.yml` | port 8090, volume `jenkins_home`, socket Docker, montage `/repo`, réseau `cicd` |

✅ **Point de contrôle** : le tableau de bord affiche le message système du lab et un job
**`tasks-api`**. Sa page « Configurer » (*Configure*) montre « Pipeline script from SCM »,
le dépôt `file:///repo` (ou votre URL GitHub), la branche `*/main` et le script `Jenkinsfile`.

💡 **À retenir** : **Configuration as Code** — le serveur de CI se reconstruit à l'identique
avec une commande. Plus de « Jenkins flocon de neige » configuré à la main que personne
n'ose redémarrer.

---

## Étape 4 — Premier pipeline : lire le Jenkinsfile stage par stage

🎯 **Objectif** : lancer le pipeline et comprendre chacun de ses 10 stages.

Dans Jenkins : job `tasks-api` → **Lancer un build** (*Build Now*).
Le premier build est long (téléchargement de Maven et des dépendances, mises ensuite en cache
dans `jenkins_home`). Pendant ce temps, ouvrez le `Jenkinsfile` :

| # | Stage | Commande principale | Ce qui fait échouer le build |
|---|---|---|---|
| — | `options` / `parameters` | timestamps, timeout 60 min, 15 builds gardés, pas de builds concurrents | — |
| 1 | Checkout | `checkout scm` + `git log -1` | dépôt / branche introuvable |
| 2 | Lint | `./mvnw -B checkstyle:check` | une violation Checkstyle |
| 3 | Tests unitaires & intégration | `./mvnw -B verify` + `junit` + `recordCoverage` + `archiveArtifacts` | un test rouge, couverture < 70 % |
| 4 | Image Docker | `docker build -t tasks-api:<sha court>` | Dockerfile cassé |
| 5 | Scan | Trivy : `--severity CRITICAL --ignore-unfixed --exit-code 1` | une faille critique corrigeable |
| 6 | Déploiement staging | `docker compose ... --env-file deploy/staging.env up -d --wait` | conteneur non *healthy* |
| 7 | Smoke test staging | `curl --retry ... http://tasks-staging-api:8080/actuator/health` | API qui ne répond pas, mauvaise version |
| 8 | Approbation | `input` (30 min max), **seulement sur `main`** | refus ou délai dépassé |
| 9 | Déploiement production | même compose, `deploy/prod.env` | conteneur non *healthy* |
| 10 | Smoke test prod | `curl ... http://tasks-prod-api:8080/actuator/health` | API qui ne répond pas |

Le build va **s'arrêter au stage « Approbation »** et attendre une décision humaine. Pour
l'instant, cliquez sur **Abort** (on y revient à l'étape 9).

Explorez la page du build : vue des stages, **Console Output** (*Sortie de la console*),
**Test Result**, **Coverage**, et l'artefact `tasks-api.jar`.

✅ **Point de contrôle** :
- les stages 1 à 7 sont **verts** ; « Approbation » est interrompu (gris) par votre Abort ;
- le log du Checkout affiche `Commit <sha> - <auteur> - Lab de départ` ;
- l'onglet *Test Result* affiche **19 tests, 0 échec** ;
- `docker ps` montre `tasks-staging-api` et `tasks-staging-db` (état `healthy`).

💡 **À retenir** : **pipeline as code** — le pipeline est versionné avec l'application,
relu en code review, et chaque build est rattaché à un commit. Comparez avec le `.yml`
GitHub Actions vu en cours : mêmes concepts (stages ≈ jobs/steps, `post` ≈ `if: always()`).

---

## Étape 5 — Casser un test volontairement

🎯 **Objectif** : voir un build rouge, lire le rapport JUnit, corriger.

La règle métier : `TaskService.create` **nettoie le titre (trim)** et refuse un titre vide.
Cassez-la : dans `src/main/java/fr/formation/tasks/service/TaskService.java`, méthode
`normalizeTitle`, remplacez

```java
String normalized = title == null ? "" : title.trim();
```

par

```java
String normalized = title == null ? "" : title;
```

Vérifiez d'abord en local (c'est plus rapide que la CI !) :

```bash
./mvnw -B test
```

Puis commitez et relancez le build Jenkins :

```bash
git commit -am "feat: on ne nettoie plus le titre (bug volontaire)"
```

✅ **Point de contrôle** :
- en local comme dans Jenkins : `Tests run: 19, Failures: 4` et `BUILD FAILURE` ;
- le pipeline est **rouge au stage « Tests unitaires & intégration »**, les stages suivants
  sont **sautés** : rien n'est déployé ;
- l'onglet *Test Result* liste les 4 tests en échec, avec des messages lisibles, par exemple :
  ```
  TaskServiceTest.createTrimsTitle
  expected: "Préparer le TP Jenkins"
   but was: "  Préparer le TP Jenkins  "
  ```
  et `TaskServiceTest.createRejectsBlankTitle : Expecting code to raise a throwable.`

Corrigez (annulez le commit fautif) et relancez :

```bash
git revert --no-edit HEAD
./mvnw -B test
```

✅ Le build suivant redevient vert.

💡 **À retenir** : le pipeline est un **filet de sécurité** : un bug détecté par un test ne
peut pas atteindre staging. *Fail fast* : les étapes rapides et bon marché d'abord.
Corriger = un **nouveau commit** (ici un `revert`), jamais une modification à la main sur le serveur.

---

## Étape 6 — Casser le lint et la couverture : les *quality gates*

🎯 **Objectif** : comprendre les portes de qualité automatiques.

**6a. Lint.** Ajoutez un import inutile dans `TaskController.java`, juste après
`import java.util.List;` :

```java
import java.util.Map;
```

```bash
./mvnw -B checkstyle:check
git commit -am "chore: import inutile (lint volontairement cassé)"
```

✅ **Point de contrôle** : stage **Lint** rouge (et Tests sauté) avec :
```
[ERROR] .../TaskController.java:8:8: Unused import - java.util.Map. [UnusedImports]
```
Corrigez avec `git revert --no-edit HEAD`.

**6b. Couverture.** Dans `pom.xml`, montez le seuil :

```xml
<jacoco.line.minimum>0.99</jacoco.line.minimum>
```

(ou, sans rien modifier : `./mvnw -B verify -Djacoco.line.minimum=0.99`)

```bash
./mvnw -B verify
git commit -am "build: seuil de couverture à 99 %"
```

✅ **Point de contrôle** : tous les tests passent, et pourtant le build échoue :
```
Rule violated for bundle tasks-api: lines covered ratio is 0.97, but expected minimum is 0.99
```
Remettez `0.70` (`git revert --no-edit HEAD`).

Bonus : ouvrez `config/checkstyle.xml` (4 règles seulement) et `pom.xml` (plugins
`maven-checkstyle-plugin` et `jacoco-maven-plugin`).

💡 **À retenir** : une **quality gate** est un critère objectif, automatisé, non négociable,
qui bloque la livraison (lint, tests, couverture, sécurité). Le seuil se fixe en équipe :
trop bas il ne sert à rien, trop haut il pousse à écrire des tests inutiles.

---

## Étape 7 — Image Docker et scan de vulnérabilités

🎯 **Objectif** : comprendre l'artefact livré et son contrôle de sécurité.

Lisez le `Dockerfile` (multi-étapes, cache des dépendances, utilisateur non-root, HEALTHCHECK),
puis inspectez l'image produite par Jenkins :

```bash
docker image ls tasks-api                       # un tag par commit construit
SHA=$(git rev-parse --short=7 HEAD)
docker image inspect tasks-api:$SHA --format '{{.Config.User}} {{json .Config.Healthcheck.Test}}'
docker run --rm --entrypoint id tasks-api:$SHA  # l'appli ne tourne PAS en root
docker history tasks-api:$SHA | head -15        # les couches de l'image
```

Relancez le scan à la main, en élargissant aux failles **HIGH** :

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v trivy-cache:/root/.cache/ aquasec/trivy:0.76.2 \
  image --severity HIGH,CRITICAL --ignore-unfixed tasks-api:$SHA
```

✅ **Point de contrôle** :
- l'image est taguée avec le **SHA court du commit** (même valeur que dans le log du Checkout) ;
- `id` affiche `uid=...(app) gid=...(app)` ;
- le stage **Scan** du pipeline était vert (aucune faille CRITICAL corrigeable).
  Le rapport manuel peut lister des HIGH : discutez-en, le seuil du pipeline est un choix.

💡 **À retenir** : **build once, deploy many** — on construit UNE image, identifiée par le
commit, et c'est **la même** qui ira en staging puis en prod. Le scan est une quality gate
de sécurité (*shift left*) ; `SKIP_SCAN` n'existe que pour le mode hors-ligne.

---

## Étape 8 — Déploiement staging et smoke test

🎯 **Objectif** : comprendre comment un même fichier compose sert à plusieurs environnements.

Comparez `deploy/staging.env` et `deploy/prod.env`, puis lisez `deploy/compose.yml` :
seules les **variables** changent (nom, port, mots de passe), le reste est identique.

```bash
docker ps --filter name=tasks-staging
curl http://localhost:8081/actuator/info
curl -X POST http://localhost:8081/api/tasks -H 'Content-Type: application/json' \
  -d '{"title":"Tâche créée en staging"}'
curl http://localhost:8081/api/tasks
docker network inspect cicd --format '{{range .Containers}}{{.Name}} {{end}}'
```

Regardez le log du stage « Smoke test staging » dans Jenkins.

✅ **Point de contrôle** :
- `/actuator/info` sur le port **8081** contient `"env":"staging"` et une version
  `1.0.0-SNAPSHOT+<sha>` qui correspond au **dernier commit construit** ;
- les données sont stockées dans **PostgreSQL** (`tasks-staging-db`), pas dans H2 ;
- le réseau `cicd` contient `lab-jenkins` et `tasks-staging-api` : c'est ainsi que Jenkins
  appelle `http://tasks-staging-api:8080` pendant le smoke test.

💡 **À retenir** : **parité des environnements** — mêmes images, même fichier de
déploiement, seule la configuration change (principe *12-factor* : config dans
l'environnement). Le **smoke test** vérifie en quelques secondes que le déploiement est
vivant et que c'est **la bonne version** — ce n'est pas un test fonctionnel complet.

---

## Étape 9 — Approbation manuelle et mise en production

🎯 **Objectif** : franchir la porte manuelle vers la prod.

Relancez un build (*Build Now*). Quand il atteint **Approbation**, survolez le stage
(ou ouvrez *Console Output*) : Jenkins demande
`Déployer tasks-api:<sha> en PRODUCTION ?` → cliquez **Déployer en prod**.

```bash
docker ps --filter name=tasks-prod
curl http://localhost:8082/actuator/info
curl http://localhost:8082/api/tasks      # vide : la prod a sa PROPRE base
curl http://localhost:8081/api/tasks      # la tâche créée en staging est toujours là
```

✅ **Point de contrôle** :
- les 10 stages sont verts ;
- `/actuator/info` sur **8082** affiche `"env":"prod"` et **la même version** que staging ;
- les bases staging et prod sont séparées.

Questions à discuter :
- Pourquoi l'approbation n'a-t-elle lieu que sur `main` ? (voir `isMainBranch()` dans le Jenkinsfile)
- Que se passe-t-il si personne ne répond pendant 30 minutes ?
- Comment restreindre qui peut approuver ? (paramètre `submitter` de `input`)

💡 **À retenir** : **Continuous Delivery** = chaque commit sur `main` est *livrable* et
la mise en prod est une décision humaine en un clic. **Continuous Deployment** = on retire
cette porte. Comparez avec les *environments* + *required reviewers* de GitHub Actions.

---

## Étape 10 — Rollback avec `ROLLBACK_TAG`

🎯 **Objectif** : revenir à la version précédente en prod sans reconstruire.

1. Notez le tag actuellement en prod :
   ```bash
   curl -s http://localhost:8082/actuator/info
   docker image ls tasks-api
   ```
2. Livrez une « v2 » : dans `src/main/resources/application.yml`, remplacez
   `name: tasks-api` (sous `info.app`) par `name: tasks-api-v2`, puis
   ```bash
   git commit -am "feat: v2"
   ```
   Lancez un build, approuvez : la prod affiche `"name":"tasks-api-v2"`.
3. « La v2 a un bug en prod ! » → job `tasks-api` → **Lancer un build avec des paramètres**
   (*Build with Parameters*) :
   - `ROLLBACK_TAG` = le tag noté à l'étape 1 (7 caractères, ex. `a1b2c3d`)
   - `ROLLBACK_ENV` = `prod`
4. Approuvez, puis :
   ```bash
   curl -s http://localhost:8082/actuator/info
   ```

✅ **Point de contrôle** :
- le build s'appelle `#N ROLLBACK <tag> -> prod` ;
- Lint, Tests, Image Docker, Scan et les stages staging sont **sautés** (gris) : le rollback
  prend quelques secondes ;
- `/actuator/info` sur 8082 affiche de nouveau `"name":"tasks-api"` et l'ancien SHA ;
- avec un tag inexistant, le build échoue proprement : `Image tasks-api:xxxx introuvable`.

💡 **À retenir** : un rollback rapide est possible **parce que** les images sont immuables
et taguées par commit (on redéploie un artefact déjà testé). Attention : on ne « rollback »
pas une base de données aussi facilement — les migrations Flyway doivent rester
**rétro-compatibles** (*expand / contract*). Après un rollback, la correction repart du
pipeline normal (nouveau commit).

---

## Étape 11 (bonus) — Le même pipeline en GitHub Actions

🎯 **Objectif** : comparer Jenkins et GitHub Actions sur la même application.

Lisez `.github/workflows/ci.yml`, puis poussez le lab sur votre dépôt GitHub (option B de
l'étape 0) et ouvrez l'onglet **Actions**.

✅ **Point de contrôle** : le job « Lint, tests et couverture » est vert, le résumé affiche
le tableau des tests, l'artefact `jacoco-report` est téléchargeable ; sur `main`, le job
« Image Docker -> GHCR » publie `ghcr.io/<votre-compte>/tasks-api` (onglet *Packages*),
puis l'image est déployée en `dev` puis en `prod` (voir [`DEMO-GHA.md`](DEMO-GHA.md)).

| Critère | Jenkins | GitHub Actions |
|---|---|---|
| Hébergement | vous l'installez, le mettez à jour, le sécurisez | SaaS (runners hébergés) ou runners auto-hébergés |
| Définition du pipeline | `Jenkinsfile` (Groovy déclaratif) | YAML dans `.github/workflows/` |
| Déclencheurs | Poll SCM, webhooks, cron, manuel, autres jobs | événements GitHub (`push`, `pull_request`, `workflow_dispatch`, `schedule`...) |
| Extensions | ~2000 plugins (à maintenir, compatibilité) | Marketplace d'actions (à épingler par SHA) |
| Approbation manuelle | `input` dans le pipeline | *environments* + *required reviewers* |
| Secrets | Jenkins Credentials | *secrets* GitHub (repo / environnement / organisation) |
| Rapports tests / couverture | plugins JUnit, Coverage (historique, graphes) | artefacts, *job summary*, actions tierces |
| Coût | serveurs + temps d'administration | minutes gratuites puis facturation à l'usage |
| Idéal pour | on-premise, réseau isolé, besoins très spécifiques | code déjà sur GitHub, démarrage rapide |

💡 **À retenir** : les **concepts** (stages, quality gates, artefacts, environnements,
approbation, rollback) sont les mêmes ; seul l'outil change.

---

## Étape 12 (bonus) — Déclencher le pipeline automatiquement

🎯 **Objectif** : ne plus cliquer sur « Lancer un build ».

**12a. Poll SCM (fonctionne hors-ligne avec `file:///repo`)** — ajoutez dans le
`Jenkinsfile`, juste après le bloc `options { ... }` :

```groovy
    triggers {
        pollSCM('H/2 * * * *')   // vérifie toutes les ~2 minutes s'il y a un nouveau commit
    }
```

Commitez, lancez **un** build à la main (pour que Jenkins enregistre le déclencheur), puis
faites un nouveau commit et attendez.

✅ Un build démarre tout seul ; sa cause est « Started by an SCM change ».

**12b. Webhook GitHub (option B)** — remplacez `pollSCM` par `githubPush()` (plugin `github`,
déjà installé) et ajoutez dans votre dépôt GitHub un webhook vers
`http://<adresse-publique>:8090/github-webhook/`. Jenkins doit être joignable depuis
Internet : utilisez un tunnel (smee.io, ngrok...) et **changez d'abord le mot de passe admin**.

**12c. Multibranch Pipeline** — *Nouveau Item* (*New Item*) → *Multibranch Pipeline* →
source Git `file:///repo`. Jenkins crée un job par branche ; créez une branche :

```bash
git switch -c feature/ma-feature && git commit --allow-empty -m "test multibranch" && git switch main
```

✅ La branche `feature/ma-feature` est construite et déployée en staging, mais **sans**
stages Approbation / Prod (réservés à `main`, via `BRANCH_NAME`).

💡 **À retenir** : le **polling** interroge le dépôt (simple, mais en retard et coûteux),
le **webhook** est notifié instantanément (réactif, mais Jenkins doit être joignable).
Le **multibranch** applique la même stratégie à chaque branche et PR.

---

## Grille d'évaluation

| # | Critère | Preuve attendue | Points |
|---|---|---|---|
| 1 | API lancée et testée en local, rapport JaCoCo lu | capture `curl` 201 + `Location`, couverture relevée | 2 |
| 2 | Jenkins démarré par JCasC, job `tasks-api` présent | capture du tableau de bord | 2 |
| 3 | Premier pipeline vert jusqu'au smoke test staging | vue des stages + onglet *Test Result* | 3 |
| 4 | Test cassé volontairement, diagnostic, correction | build rouge + message d'échec + build vert suivant | 3 |
| 5 | Quality gates : lint et couverture expliqués | les 2 messages d'erreur + explication en 2 phrases | 2 |
| 6 | Image Docker et scan compris | tag = SHA, `id` non-root, rôle du scan expliqué | 2 |
| 7 | Approbation et déploiement prod | `/actuator/info` prod = même version que staging | 2 |
| 8 | Rollback réussi | build `ROLLBACK`, version précédente en prod | 2 |
| 9 | Compréhension : réponses aux questions de l'étape 9 + comparaison Jenkins / GitHub Actions | réponses écrites | 2 |
| | **Total** | | **20** |
| + | Bonus : étape 11 ou 12 réalisée | capture | +1 |
