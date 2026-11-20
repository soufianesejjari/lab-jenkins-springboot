# Lab CI/CD — Jenkins + Spring Boot (`tasks-api`)

Travaux pratiques de la formation CI/CD (formateur : Soufiane SEJJARI).

Vous allez construire, pas à pas, un pipeline **Jenkins** complet pour une petite API REST
**Spring Boot** de gestion de tâches : lint, tests, couverture, image Docker, scan de
vulnérabilités, déploiement **staging**, approbation manuelle, déploiement **prod** et **rollback**.

- Guide étudiant pas à pas : [`LAB.md`](LAB.md)
- Notes formateur : [`FORMATEUR.md`](FORMATEUR.md)
- **Démo GitHub Actions (dev / prod, variables, secrets, approbation)** : [`DEMO-GHA.md`](DEMO-GHA.md)

---

## Contenu du dépôt

```
lab-jenkins-springboot/
├── src/main/java/fr/formation/tasks/   API : controller / service / repository / DTO
├── src/main/resources/                 application*.yml + migration Flyway V1__create_task.sql
├── src/test/java/...                   tests unitaires, @WebMvcTest, @SpringBootTest
├── config/checkstyle.xml               règles de lint (petit jeu de règles)
├── pom.xml                             Spring Boot 4.2, Java 21, JaCoCo (>= 70 %), Checkstyle
├── mvnw / .mvn/                        Maven Wrapper (pas besoin d'installer Maven)
├── Dockerfile / .dockerignore          image multi-étapes, non-root, HEALTHCHECK
├── Jenkinsfile                         LE pipeline (10 stages, commenté)
├── deploy/compose.yml                  déploiement staging ET prod (même fichier)
├── deploy/staging.env, deploy/prod.env ce qui change d'un environnement à l'autre
├── jenkins/                            Jenkins local : Dockerfile, plugins.txt, casc.yaml, compose.yml
├── .github/workflows/ci.yml            GitHub Actions : build → image → dev → prod (approbation)
└── .github/workflows/deploy.yml        déploiement réutilisable (environnement, vars, secrets)
```

## Architecture

```
  Votre machine (macOS / Linux / Windows + Docker Desktop)
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │                                                                              │
 │  dossier du lab (dépôt Git) ──monté en lecture seule──┐                      │
 │                                                       ▼  file:///repo        │
 │   navigateur ──:8090──►  ┌─────────────────────────────────────┐             │
 │                          │  lab-jenkins (jenkins/jenkins LTS)  │             │
 │                          │  JDK 21 + ./mvnw + CLI docker       │             │
 │                          └──────────────┬──────────────────────┘             │
 │                                         │ /var/run/docker.sock               │
 │                                         ▼                                    │
 │                          ┌────────────────────────────┐                      │
 │                          │  Démon Docker de l'hôte    │ docker build / run   │
 │                          └─────────────┬──────────────┘ docker compose       │
 │                                        │                                     │
 │      réseau Docker « cicd » ───────────┼──────────────────────────────┐      │
 │      ┌─────────────────────────────┐   │   ┌─────────────────────────┐│      │
 │      │ STAGING (tasks-staging)     │   │   │ PROD (tasks-prod)       ││      │
 │      │ tasks-staging-api :8081 ◄───┼───┘   │ tasks-prod-api  :8082   ││      │
 │      │ tasks-staging-db (Postgres) │       │ tasks-prod-db (Postgres)││      │
 │      └─────────────────────────────┘       └─────────────────────────┘│      │
 │                                                                       │      │
 │   Jenkins est aussi sur « cicd » ─► smoke tests : http://tasks-<env>-api:8080│
 └──────────────────────────────────────────────────────────────────────────────┘

 Pipeline : Checkout → Lint → Tests → Image Docker → Scan → Staging → Smoke test
            → Approbation (main) → Production → Smoke test
```

## Prérequis

| Outil | Version | Vérification |
|---|---|---|
| Git | 2.30+ | `git --version` |
| JDK | 21 ou plus récent (21 recommandé) | `java -version` |
| Docker Desktop (ou Docker Engine + plugin compose) | récent, **6 Go de RAM** alloués | `docker info` puis `docker compose version` |
| curl | n'importe laquelle | `curl --version` |
| Accès Internet | au moins pour le 1er lancement (images Docker, dépendances Maven, base Trivy) | |

Maven n'est **pas** nécessaire : le Maven Wrapper (`./mvnw`) le télécharge tout seul.

## Démarrage rapide

```bash
# 1. Le dossier du lab doit être un dépôt Git (Jenkins lit les COMMITS, pas vos fichiers)
cd lab-jenkins-springboot
git init -b main && git add -A && git commit -m "Lab de départ"

# 2. L'API en local (H2 en mémoire)
./mvnw -B verify                 # lint + tests + couverture
./mvnw spring-boot:run           # http://localhost:8080/api/tasks

# 3. Jenkins (dans un autre terminal)
cd jenkins
docker compose up -d --build     # premier lancement : 5 à 10 min
docker compose logs -f jenkins   # attendre « Jenkins is fully up and running »
# => http://localhost:8090  (admin / admin)  -> job « tasks-api » -> « Lancer un build »
```

Alternative GitHub : poussez le lab sur **votre** dépôt GitHub (fork ou nouveau dépôt), puis
```bash
cd jenkins
TASKS_REPO_URL=https://github.com/<votre-compte>/lab-jenkins-springboot.git docker compose up -d --build
```
(dépôt public, ou ajoutez des credentials Git dans Jenkins pour un dépôt privé).

## Ports

| Service | URL sur votre machine | Nom sur le réseau Docker `cicd` |
|---|---|---|
| API en local (`./mvnw spring-boot:run`) | http://localhost:8080 | — |
| Jenkins | http://localhost:8090 | `lab-jenkins:8080` |
| API **staging** | http://localhost:8081 | `tasks-staging-api:8080` |
| API **prod** | http://localhost:8082 | `tasks-prod-api:8080` |

Endpoints utiles : `/api/tasks`, `/actuator/health`, `/actuator/info`.

## Sécurité — à lire (et à expliquer)

Ce lab fait volontairement des choix **inacceptables en production** pour rester simple :

1. **`/var/run/docker.sock` monté dans Jenkins.** Qui contrôle le socket Docker contrôle la
   machine : un `Jenkinsfile` malveillant peut lancer un conteneur `--privileged` qui monte
   `/` de l'hôte. En entreprise : agents Jenkins dédiés (VM ou Kubernetes), Docker *rootless*,
   builds sans démon (Kaniko, Buildah, BuildKit distant), et jamais de build de code non revu
   sur le contrôleur.
2. **`user: root` pour le conteneur Jenkins** (`jenkins/compose.yml`) : évite les erreurs
   « permission denied » sur le socket. Lab uniquement (voir Dépannage pour l'alternative).
3. **`admin` / `admin`** (JCasC, variables `JENKINS_ADMIN_ID` / `JENKINS_ADMIN_PASSWORD`) :
   **lab local uniquement**. Jenkins n'écoute que sur votre machine ; ne l'exposez jamais
   (pas de redirection de port sur une box, pas de tunnel public sans changer le mot de passe).
4. **Mots de passe PostgreSQL en clair** dans `deploy/*.env` : en vrai, ils viennent d'un
   coffre (Jenkins Credentials + `withCredentials`, Vault, secrets GitHub...).
5. **`git config --system safe.directory '*'`** dans l'image Jenkins : nécessaire pour lire le
   dossier monté `/repo` qui appartient à un autre utilisateur. Lab uniquement.
6. Les builds s'exécutent sur le **contrôleur** Jenkins (2 exécuteurs) : déconseillé en
   production, où l'on utilise des agents.

## Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `docker info` ne répond pas / bloque | Docker Desktop arrêté, en pause ou bloqué | Ouvrir Docker Desktop, « Resume » / « Restart » ; vérifier `docker info` |
| Build Maven tué (`exit code 137`), conteneurs qui redémarrent | Pas assez de RAM pour la VM Docker | Docker Desktop → Settings → Resources → **6 Go** minimum (8 Go confortable) |
| `Bind for 0.0.0.0:8090 failed: port is already allocated` (ou 8080/8081/8082) | Un autre service utilise le port | `lsof -i :8090` ; arrêter le service, ou changer le port hôte dans le compose (`"8091:8080"`) |
| `./mvnw spring-boot:run` : `Port 8080 was already in use` | Autre appli sur 8080 | `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8085` |
| Checkout Jenkins : `Checkout of Git remote 'file:///repo' aborted because it references a local directory` | Propriété `ALLOW_LOCAL_CHECKOUT` absente | Elle est dans `JAVA_OPTS` de `jenkins/Dockerfile` : reconstruire (`docker compose up -d --build`) |
| Checkout : `couldn't find remote ref refs/heads/main` / `does not appear to be a git repository` | Pas de `git init`, pas de commit, ou branche `master` | `git init -b main` + commit ; ou `git branch -m master main` ; ou `TASKS_REPO_BRANCH='*/master'` |
| Mes modifications ne sont pas prises en compte par Jenkins | Jenkins lit les **commits**, pas les fichiers modifiés | `git add -A && git commit -m "..."` puis relancer le build |
| `permission denied while trying to connect to the Docker daemon socket` | L'utilisateur `jenkins` (uid 1000) n'a pas accès au socket | Lab : garder `user: root` (déjà le cas). Alternative plus propre sous Linux : retirer `user: root` et ajouter `group_add: ["<GID>"]` avec `<GID>` = `stat -c %g /var/run/docker.sock` sur l'hôte, puis `docker compose down -v && docker compose up -d --build` (le volume jenkins_home créé en root doit être recréé) |
| `/var/run/docker.sock: no such file` (macOS) | Socket par défaut désactivé | Docker Desktop → Settings → Advanced → cocher « Allow the default Docker socket to be used » |
| Mac Apple Silicon (arm64) | Toutes les images utilisées existent en arm64 (jenkins, maven, temurin alpine, postgres, trivy) | Rien à faire. Si `eclipse-temurin:21-jre-alpine` posait problème, utiliser `eclipse-temurin:21-jre-noble` et remplacer `addgroup/adduser` par `groupadd -r app && useradd -r -g app app` |
| `network cicd declared as external, but could not be found` | Jenkins pas démarré (c'est son compose qui crée le réseau) | `cd jenkins && docker compose up -d` (ou `docker network create cicd`) |
| Stage **Scan** en échec sans faille (timeout, `failed to download vulnerability DB`) | Pas d'Internet / GitHub Container Registry inaccessible | Relancer ; en mode hors-ligne : « Build with Parameters » → cocher `SKIP_SCAN` |
| Stage **Scan** rouge avec une CVE CRITICAL | C'est le but du quality gate | Lire la CVE, mettre à jour l'image de base / la dépendance, reconstruire |
| `dependency failed to start: container tasks-staging-db is unhealthy` | Démarrage Postgres lent | Relancer le build ; vérifier la RAM de Docker |
| Smoke test : `Could not resolve host: tasks-staging-api` | Jenkins ou l'API pas sur le réseau `cicd` | `docker network inspect cicd` : `lab-jenkins` et `tasks-staging-api` doivent y être |
| Bouton « Build with Parameters » absent | Les paramètres sont déclarés dans le Jenkinsfile | Lancer **un** build normal d'abord : Jenkins enregistre alors les paramètres |
| Mot de passe admin refusé | Volume `jenkins_home` créé avec d'autres identifiants | `docker compose down -v` dans `jenkins/` puis relancer (efface l'historique Jenkins) |

## Tout arrêter / tout nettoyer

```bash
# Environnements déployés (TAG n'importe quelle valeur : il est juste exigé par le fichier)
TAG=x docker compose -p tasks-staging --env-file deploy/staging.env -f deploy/compose.yml down -v
TAG=x docker compose -p tasks-prod    --env-file deploy/prod.env    -f deploy/compose.yml down -v

# Jenkins (-v efface aussi jenkins_home : jobs, historique, cache Maven)
cd jenkins && docker compose down -v

# Optionnel : images du lab et cache Trivy
docker image rm $(docker image ls -q tasks-api) lab-jenkins:local
docker volume rm trivy-cache
```
