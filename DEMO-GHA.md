# Démo formateur : CI/CD GitHub Actions, dev et prod

> Preuve de concept **montrée** par le formateur, pas un TP.
> Message à faire passer : **une seule image, plusieurs environnements.
> Le code ne change pas ; seule la configuration change.**

```
push sur main
   │
   ▼
build ──► image ──► deploy-dev ──► deploy-prod
lint       GHCR      automatique     ⏸ attend ton approbation
tests      tag=SHA   base tasks_dev  base tasks_prod
```

Fichiers à montrer :

| Fichier | Ce qu'on y voit |
|---|---|
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | Le pipeline complet : 4 jobs, `needs`, `if`, `permissions` |
| [.github/workflows/deploy.yml](.github/workflows/deploy.yml) | Le déploiement réutilisable : `environment:`, `vars`, `secrets`, base de données en service |
| [src/main/resources/application-dev.yml](src/main/resources/application-dev.yml) | La config Spring ne contient **aucune valeur**, seulement des `${VARIABLES}` |
| [InfoController.java](src/main/java/fr/formation/tasks/api/InfoController.java) | `GET /api/info` : l'app dit dans quel environnement elle tourne et quelle base elle utilise |

---

## Préparation (la veille, une seule fois)

1. **Pousser le repo** sur GitHub. Le premier run crée tout seul les environnements `dev` et `prod`.
2. **Settings → Environments → `prod`** :
   - cocher **Required reviewers** et s'ajouter soi-même ;
   - *Deployment branches* : `main` seulement.
3. **Configurer chaque environnement** (`dev` puis `prod`). C'est facultatif : sans configuration, le workflow prend des valeurs par défaut. Mais c'est le cœur de la démo :

   | | dev | prod |
   |---|---|---|
   | Variable `DB_NAME` | `tasks_dev` | `tasks_prod` |
   | Variable `DB_USER` | `dev_user` | `prod_user` |
   | Secret `DB_PASSWORD` | `dev-pass-123` | un mot de passe long, différent |

4. **Lancer un run complet une fois** (onglet Actions → *CI/CD tasks-api* → *Run workflow*) et approuver la prod.
   Ça télécharge les caches, et tu as un **run vert de secours** à montrer si le réseau tombe pendant le cours.
5. Préparer un petit changement prêt à pousser, par exemple le titre `<h1>` de [index.html](src/main/resources/static/index.html) ou un texte dans le README.

---

## Démo 1 : « même jar, deux mondes » (en local, sans Docker)

Seulement Java 21. Deux terminaux côte à côte :

```bash
./mvnw -q package -DskipTests
```

```bash
APP_ENV=dev  SERVER_PORT=8081 SPRING_DATASOURCE_URL='jdbc:h2:mem:tasks_dev;DB_CLOSE_DELAY=-1'  SPRING_DATASOURCE_USERNAME=dev_user  java -jar target/tasks-api.jar
```

```bash
APP_ENV=prod SERVER_PORT=8082 SPRING_DATASOURCE_URL='jdbc:h2:mem:tasks_prod;DB_CLOSE_DELAY=-1' SPRING_DATASOURCE_USERNAME=prod_user java -jar target/tasks-api.jar
```

Ouvrir http://localhost:8081 et http://localhost:8082 côte à côte : **bandeau gris DEV**, **bandeau rouge PROD**.

- Ajouter une tâche dans DEV, puis rafraîchir PROD : elle n'y est pas. **Données isolées.**
- Montrer les cartes « Base de données » : `TASKS_DEV` d'un côté, `TASKS_PROD` de l'autre.
- Question à la salle : *« Qu'est-ce qui a changé dans le code entre les deux ? »* Rien : même fichier `tasks-api.jar`.

---

## Démo 2 : le pipeline GitHub Actions

1. **Lire `ci.yml` ensemble** (avec l'antisèche à côté) :
   - `on:` : push sur main, pull request, bouton manuel ;
   - `needs:` : l'ordre des jobs ;
   - `if:` : pas d'image ni de déploiement sur une pull request ;
   - `permissions:` : seul le job `image` peut écrire dans le registre ;
   - `uses: ./.github/workflows/deploy.yml` deux fois, avec `environment: dev` puis `prod` : **même code de déploiement**.
2. **Lire `deploy.yml`** et s'arrêter sur trois lignes :
   - `environment: name: …` : le job « entre » dans l'environnement, lit **ses** variables et **ses** secrets, et respecte **ses** règles ;
   - `vars.DB_NAME` et `secrets.DB_PASSWORD` : où vit la config ;
   - `services: db:` : chaque environnement a sa propre base.
3. **Pousser le petit changement préparé**, puis ouvrir l'onglet **Actions** :
   - `build` passe, avec le tableau des tests dans le résumé ;
   - `image` passe : *« construite une seule fois »*, taguée avec le SHA du commit ;
   - `deploy-dev` passe tout seul : ouvrir le **résumé**, qui affiche `tasks_dev`, `dev_user` et « secret de l'environnement GitHub » ;
   - `deploy-prod` est **en attente** (orange) : *« Personne ne déploie en prod sans validation. »*
4. **Cliquer *Review deployments* → `prod` → Approve.**
   Le résumé prod affiche **la même image**, mais `tasks_prod`, `prod_user` et un autre secret.
5. **Montrer les logs du step « Déployer »** : le mot de passe apparaît `***`. GitHub masque les secrets.
6. **Montrer *Code → Deployments*** (colonne de droite du repo) : l'historique de ce qui est en dev et en prod, et quand.

### Bonus : casser pour montrer le filet de sécurité

Changer une assertion dans [TaskServiceTest.java](src/test/java/fr/formation/tasks/service/TaskServiceTest.java), puis pousser :
`build` est rouge, **aucune image n'est construite et rien n'est déployé**. Remettre le test, pousser : tout redevient vert.

---

## Si ça casse pendant la démo

| Symptôme | Cause | Réaction |
|---|---|---|
| `deploy-prod` part sans attendre | Pas de *Required reviewers* sur `prod` | Le dire : *« voilà pourquoi on protège la prod »*, puis corriger dans Settings |
| `denied` au pull de l'image | Le package GHCR n'est pas lié au repo | Package → *Package settings* → *Manage Actions access* → ajouter le repo |
| Le réseau est lent | — | Montrer le run vert de la veille (étape 4 de la préparation) |
| Le résumé affiche « valeurs par défaut » | Variables pas créées dans l'environnement | Ça marche quand même ; montrer où on les ajoute |

## Ce que la démo simule (à dire honnêtement)

Le « serveur » est le runner GitHub, détruit à la fin du job. En entreprise, le step *Déployer* ferait :
- `ssh prod "docker compose pull && docker compose up -d"` (petit projet) ;
- ou `kubectl apply` / `helm upgrade` (Kubernetes) ;
- ou rien du tout : Argo CD voit la nouvelle image et synchronise (GitOps).

**La logique ne change pas** : même image, environnement GitHub, variables et secrets, approbation, smoke test.
