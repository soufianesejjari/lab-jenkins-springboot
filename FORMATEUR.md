# Notes formateur — Lab Jenkins + Spring Boot

Document réservé au formateur (Soufiane SEJJARI). Les étudiants suivent `LAB.md`.

---

## 1. Préparation (la veille)

Sur **votre** machine, puis idéalement sur un poste étudiant type :

- [ ] Docker Desktop démarré, `docker info` répond en < 5 s, **6 Go de RAM minimum** (8 Go conseillés)
      pour la VM Docker (Jenkins + build Maven + 2 Postgres + 2 API + Trivy).
- [ ] Macs : *Settings → Advanced → « Allow the default Docker socket to be used »* coché.
- [ ] Ports libres : `lsof -i :8080 -i :8081 -i :8082 -i :8090`.
- [ ] Pré-télécharger les images (le Wi-Fi de salle ne tiendra pas 25 `docker pull` simultanés) :
  ```bash
  docker pull jenkins/jenkins:lts-jdk21
  docker pull maven:3.9-eclipse-temurin-21
  docker pull eclipse-temurin:21-jre-alpine
  docker pull postgres:16-alpine
  docker pull aquasec/trivy:0.76.2
  ```
  Pour une salle sans bon réseau : `docker save ... -o images-lab.tar` sur une clé USB,
  puis `docker load -i images-lab.tar` sur chaque poste.
- [ ] Faire tourner **un pipeline complet** de bout en bout (étapes 0 → 10) :
  le premier build télécharge Maven, les dépendances et la base Trivy (~1 Go au total).
- [ ] Vérifier que la version de Trivy épinglée (`TRIVY_IMAGE` dans le `Jenkinsfile`) ne
      remonte pas de faille CRITICAL sur l'image du jour. Si c'est le cas, c'est une très
      bonne illustration — mais prévoyez la réponse (mettre à jour l'image de base, ou
      `SKIP_SCAN` en dernier recours).
- [ ] Préparer un **plan B** : si Docker ne fonctionne pas sur un poste, l'étudiant fait les
      étapes 1, 2, 5, 6 en local avec `./mvnw` et suit le pipeline en binôme.
- [ ] Préparer le dépôt GitHub « formateur » (option B) si vous voulez faire l'étape 11.

## 2. Déroulé indicatif

| Étape | Contenu | Durée indicative |
|---|---|---|
| 0 | Prérequis, `git init` | 10 min |
| 1 | API en local, `curl` | 15 min |
| 2 | Tests + JaCoCo | 15 min |
| 3 | Démarrer Jenkins (lancer **tôt** : le build de l'image prend du temps) | 15 min |
| 4 | Premier pipeline + lecture du Jenkinsfile | 30 min |
| 5 | Casser un test | 20 min |
| 6 | Quality gates (lint, couverture) | 20 min |
| 7 | Image Docker + scan | 20 min |
| 8 | Staging + smoke test | 15 min |
| 9 | Approbation + prod | 15 min |
| 10 | Rollback | 20 min |
| 11 | Bonus GitHub Actions + comparaison | 20 min |
| 12 | Bonus déclencheurs | 15 min |
| | **Total cœur (0 → 10)** | **≈ 3 h 30** |

Astuce : faites lancer `docker compose up -d --build` (étape 3) **dès l'étape 1**, dans un
second terminal ; le temps de build de l'image Jenkins est ainsi masqué.

## 3. Erreurs fréquentes des étudiants

| Symptôme | Cause | Correction |
|---|---|---|
| Jenkins construit l'ancien code | modification non commitée | `git status` → `git commit` (Jenkins lit les commits) |
| `couldn't find remote ref refs/heads/main` | `git init` sans `-b main` (branche `master`) | `git branch -m master main` |
| `not a git repository` au Checkout | `git init` lancé dans le mauvais dossier (ou pas du tout) | `git init` **dans** `lab-jenkins-springboot/` (c'est ce dossier qui est monté en `/repo`) |
| `references a local directory` | image Jenkins non reconstruite après modif du Dockerfile | `docker compose up -d --build` |
| `./mvnw: Permission denied` (local) | bit exécutable perdu (zip, Windows) | `chmod +x mvnw` ; `git update-index --chmod=+x mvnw` |
| `/usr/bin/env: 'sh\r'` dans Jenkins | fins de ligne CRLF (Windows) | `.gitattributes` fourni ; `git add --renormalize . && git commit` |
| `permission denied ... docker.sock` | `user: root` retiré du compose | remettre `user: root` (lab) |
| Exit 137 pendant `./mvnw verify` | RAM Docker insuffisante | augmenter la RAM, fermer les autres conteneurs |
| `port is already allocated` | autre appli / ancien conteneur / API locale encore lancée sur 8080 | `docker ps`, `lsof -i :<port>` |
| Le build attend indéfiniment | il est sur « Approbation » | cliquer sur le stage → *Déployer en prod* ou *Abort* |
| Pas de « Build with Parameters » | aucun build encore exécuté | lancer un build normal d'abord |
| Rollback : `Image tasks-api:xxx introuvable` | faute de frappe, ou tag de 8+ caractères | `docker image ls tasks-api` : le tag fait **7** caractères |
| Smoke test `Could not resolve host` | réseau `cicd` supprimé / recréé pendant que Jenkins tournait | `cd jenkins && docker compose up -d --force-recreate` |
| Test d'intégration rouge uniquement dans Jenkins | quasi jamais : comparer `java -version` (21 dans Jenkins) | `./mvnw -B verify` en local avec un JDK 21 |

## 4. Résultats attendus (pour corriger vite)

- `./mvnw -B verify` : `Tests run: 19, Failures: 0`, `You have 0 Checkstyle violations.`,
  `All coverage checks have been met.`, couverture de lignes ≈ **97 %**.
- Étape 5 (trim supprimé) : `Tests run: 19, Failures: 4` —
  `TasksApiIntegrationTest.fullCrudScenario`, `TaskServiceTest.createTrimsTitle`,
  `TaskServiceTest.createRejectsBlankTitle`, `TaskServiceTest.updateChangesTitleAndStatus`.
  Message type : `expected: "Préparer le TP Jenkins" but was: "  Préparer le TP Jenkins  "`.
- Étape 6a : `TaskController.java:8:8: Unused import - java.util.Map. [UnusedImports]`.
- Étape 6b : `Rule violated for bundle tasks-api: lines covered ratio is 0.97, but expected minimum is 0.99`.
- `/actuator/info` en staging :
  ```json
  {"app":{"name":"tasks-api","env":"staging","version":"1.0.0-SNAPSHOT+a1b2c3d","commit":"a1b2c3d..."},
   "git":{},"build":{"artifact":"tasks-api","version":"1.0.0-SNAPSHOT",...}}
  ```
  (la section `git` n'est remplie que pour l'API lancée en local / le jar construit par
  Jenkins : le `.git` n'est pas envoyé dans le contexte Docker, d'où `"git":{}` dans l'image ;
  la traçabilité de l'image passe par `APP_VERSION` / `APP_COMMIT` — bonne question à poser).
- Pipeline complet sur `main` : 10 stages verts ; durée ≈ 6-10 min au 1er build,
  ≈ 2-3 min ensuite (hors temps d'approbation).
- Rollback : stages 2-7 sautés, ≈ 30 s + approbation.

## 5. Réinitialiser le lab

Sur un poste étudiant (depuis `lab-jenkins-springboot/`) :

```bash
# 1. Environnements staging / prod (+ leurs volumes Postgres)
TAG=x docker compose -p tasks-staging --env-file deploy/staging.env -f deploy/compose.yml down -v
TAG=x docker compose -p tasks-prod    --env-file deploy/prod.env    -f deploy/compose.yml down -v

# 2. Jenkins (+ jenkins_home : jobs, historique, cache Maven) — supprime aussi le réseau cicd
cd jenkins && docker compose down -v && cd ..

# 3. Images construites par le pipeline et cache Trivy (optionnel)
docker image rm $(docker image ls -q tasks-api)
docker volume rm trivy-cache

# 4. Code : revenir au commit de départ
git log --oneline            # repérer le premier commit
git reset --hard <sha-du-premier-commit>
```

Pour garder Jenkins mais repartir d'un historique de builds vide : supprimer le job dans
l'interface, puis `docker compose restart` dans `jenkins/` (JCasC le recrée au démarrage).

Ordre important : arrêtez staging/prod **avant** Jenkins, sinon `docker compose down`
dans `jenkins/` ne peut pas supprimer le réseau `cicd` (encore utilisé).

## 6. Pistes de discussion / questions d'examen

- Pourquoi ne pas utiliser `agent { docker { image 'maven:...' } }` ici ? (Jenkins est
  lui-même dans un conteneur et partage le socket de l'hôte : les chemins du workspace ne
  correspondent pas côté hôte → volumes vides.)
- Pourquoi l'image est-elle taguée par SHA et pas `latest` ? (traçabilité, rollback,
  « build once deploy many »)
- Pourquoi le stage Lint avant les tests ? (*fail fast* : quelques secondes vs une minute)
- Que manque-t-il pour une vraie prod ? (registre d'images, secrets, agents dédiés,
  notifications Slack/mail, déploiement blue/green ou canary, monitoring, sauvegardes DB)
- Que se passerait-il si deux builds déployaient en même temps ? (`disableConcurrentBuilds`)
