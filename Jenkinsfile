// =============================================================================
// Pipeline CI/CD de tasks-api (Jenkinsfile déclaratif)
//
//   CI : Checkout -> Lint -> Tests (+ JUnit, couverture) -> Image Docker -> Scan
//   CD : Staging -> Smoke test -> Approbation manuelle -> Prod -> Smoke test
//
// Particularité du lab : Jenkins tourne lui-même dans un conteneur et pilote le
// Docker de l'hôte via /var/run/docker.sock. On utilise donc `agent any` (le JDK 21
// de l'image Jenkins + ./mvnw) et NON `agent { docker { ... } }` : avec le socket
// partagé, les chemins du workspace ne correspondraient pas entre Jenkins et l'hôte.
// =============================================================================

// Vrai si le build porte sur la branche main (job pipeline simple : GIT_BRANCH = "origin/main")
def isMainBranch() {
    return ['main', 'origin/main'].contains(env.GIT_BRANCH) || env.BRANCH_NAME == 'main'
}

// Vrai si l'utilisateur a demandé un rollback (paramètre ROLLBACK_TAG renseigné)
def isRollback() {
    return params.ROLLBACK_TAG?.trim() ? true : false
}

pipeline {
    agent any

    options {
        timestamps()                                    // horodatage de chaque ligne de log
        timeout(time: 60, unit: 'MINUTES')              // un build bloqué ne monopolise pas Jenkins
        buildDiscarder(logRotator(numToKeepStr: '15'))  // on ne garde que les 15 derniers builds
        disableConcurrentBuilds()                       // un seul déploiement à la fois
        skipDefaultCheckout()                           // le checkout est fait explicitement (stage Checkout)
    }

    parameters {
        string(name: 'ROLLBACK_TAG', defaultValue: '',
               description: 'Rollback : tag d\'image existant à redéployer (ex. a1b2c3d). Vide = build normal.')
        choice(name: 'ROLLBACK_ENV', choices: ['staging', 'prod'],
               description: 'Rollback : environnement cible (ignoré si ROLLBACK_TAG est vide).')
        booleanParam(name: 'SKIP_SCAN', defaultValue: false,
                     description: 'Ne pas lancer le scan Trivy (mode hors-ligne uniquement !).')
    }

    environment {
        IMAGE_NAME = 'tasks-api'
        // Cache Maven (et Maven Wrapper) dans jenkins_home => persistant entre les builds
        MAVEN_USER_HOME = "${env.JENKINS_HOME}/.m2"
        MAVEN_ARGS = "-Dmaven.repo.local=${env.JENKINS_HOME}/.m2/repository"
        // Version de Trivy épinglée (en production : épingler aussi le digest sha256)
        TRIVY_IMAGE = 'aquasec/trivy:0.76.2'
        COMPOSE_FILE_PATH = 'deploy/compose.yml'
    }

    stages {

        // 1. Récupérer le code et afficher le commit construit
        stage('Checkout') {
            steps {
                script {
                    def scmVars = checkout scm
                    env.GIT_COMMIT = scmVars.GIT_COMMIT
                    env.GIT_BRANCH = scmVars.GIT_BRANCH
                    env.SHORT_SHA = scmVars.GIT_COMMIT.substring(0, 7)
                    // Tag de l'image à déployer : le commit courant, ou le tag demandé en rollback
                    env.TAG = isRollback() ? params.ROLLBACK_TAG.trim() : env.SHORT_SHA
                    currentBuild.displayName = isRollback() ? "#${env.BUILD_NUMBER} ROLLBACK ${env.TAG} -> ${params.ROLLBACK_ENV}"
                                                            : "#${env.BUILD_NUMBER} ${env.SHORT_SHA}"
                }
                sh 'git log -1 --pretty=format:"Commit %h - %an - %s%n"'
                echo "Branche : ${env.GIT_BRANCH} | Tag d'image : ${env.TAG} | Rollback : ${isRollback()}"
                sh 'chmod +x mvnw'
            }
        }

        // 2. Lint : Checkstyle (config/checkstyle.xml). Échec immédiat si violation.
        stage('Lint') {
            when { expression { !isRollback() } }
            steps {
                sh './mvnw -B checkstyle:check'
            }
        }

        // 3. Tests unitaires + intégration + quality gate de couverture (JaCoCo >= 70 %)
        stage('Tests unitaires & intégration') {
            when { expression { !isRollback() } }
            steps {
                sh './mvnw -B verify'
            }
            post {
                always {
                    // Rapport des tests (onglet "Test Result") même si des tests échouent
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
                    // Rapport de couverture (plugin Coverage, format JaCoCo).
                    // Si le plugin « Coverage » n'est pas installé sur ce Jenkins, on ne fait pas
                    // échouer le build : on archive le rapport HTML JaCoCo à la place.
                    script {
                        try {
                            recordCoverage(tools: [[parser: 'JACOCO', pattern: 'target/site/jacoco/jacoco.xml']])
                        } catch (NoSuchMethodError err) {
                            echo 'Plugin Coverage absent : rapport JaCoCo archivé en HTML.'
                            archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
                        }
                    }
                }
                success {
                    // Le jar devient un "artefact" téléchargeable depuis la page du build
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }
        }

        // 4. Construire l'image Docker, taguée avec le commit (traçabilité code <-> image)
        stage('Image Docker') {
            when { expression { !isRollback() } }
            steps {
                script {
                    def pomVersion = readMavenPom(file: 'pom.xml').version
                    env.APP_VERSION = "${pomVersion}+${env.SHORT_SHA}"
                }
                sh '''
                    docker build \
                      --build-arg APP_VERSION="$APP_VERSION" \
                      --build-arg APP_COMMIT="$GIT_COMMIT" \
                      -t "$IMAGE_NAME:$TAG" .
                    docker image ls "$IMAGE_NAME"
                '''
            }
        }

        // 5. Scan de vulnérabilités : le build échoue s'il reste une faille CRITIQUE corrigeable
        stage('Scan') {
            when { expression { !isRollback() && !params.SKIP_SCAN } }
            steps {
                // Trivy tourne dans un conteneur et lit l'image via le socket Docker.
                // Le volume trivy-cache évite de retélécharger la base de vulnérabilités à chaque build.
                sh '''
                    docker run --rm \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      -v trivy-cache:/root/.cache/ \
                      "$TRIVY_IMAGE" image \
                        --severity CRITICAL --ignore-unfixed --exit-code 1 --no-progress \
                        "$IMAGE_NAME:$TAG"
                '''
            }
        }

        // 6. Déploiement en staging (docker compose + deploy/staging.env)
        stage('Déploiement staging') {
            when { expression { !isRollback() || params.ROLLBACK_ENV == 'staging' } }
            steps {
                sh 'docker image inspect "$IMAGE_NAME:$TAG" > /dev/null || { echo "Image $IMAGE_NAME:$TAG introuvable"; exit 1; }'
                sh 'docker compose -p tasks-staging --env-file deploy/staging.env -f "$COMPOSE_FILE_PATH" up -d --wait --wait-timeout 180'
            }
            post {
                failure {
                    sh 'docker compose -p tasks-staging --env-file deploy/staging.env -f "$COMPOSE_FILE_PATH" logs --tail 80 || true'
                }
            }
        }

        // 7. Smoke test : l'application répond-elle, avec la bonne version ?
        stage('Smoke test staging') {
            when { expression { !isRollback() || params.ROLLBACK_ENV == 'staging' } }
            steps {
                // Jenkins et l'API sont sur le réseau Docker "cicd" : on appelle le conteneur par son nom
                sh '''
                    curl -fsS --retry 15 --retry-delay 4 --retry-all-errors \
                      http://tasks-staging-api:8080/actuator/health | tee health.json
                    grep -q '"status":"UP"' health.json
                    curl -fsS http://tasks-staging-api:8080/actuator/info | tee info.json
                    grep -q "$TAG" info.json
                '''
            }
        }

        // 8. Porte manuelle avant la production (uniquement sur main)
        stage('Approbation') {
            when { expression { (!isRollback() && isMainBranch()) || (isRollback() && params.ROLLBACK_ENV == 'prod') } }
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: "Déployer ${env.IMAGE_NAME}:${env.TAG} en PRODUCTION ?",
                          ok: 'Déployer en prod'
                }
            }
        }

        // 9. Déploiement en production : MÊME image, MÊME compose, autre fichier d'environnement
        stage('Déploiement production') {
            when { expression { (!isRollback() && isMainBranch()) || (isRollback() && params.ROLLBACK_ENV == 'prod') } }
            steps {
                sh 'docker image inspect "$IMAGE_NAME:$TAG" > /dev/null || { echo "Image $IMAGE_NAME:$TAG introuvable"; exit 1; }'
                sh 'docker compose -p tasks-prod --env-file deploy/prod.env -f "$COMPOSE_FILE_PATH" up -d --wait --wait-timeout 180'
            }
            post {
                failure {
                    sh 'docker compose -p tasks-prod --env-file deploy/prod.env -f "$COMPOSE_FILE_PATH" logs --tail 80 || true'
                }
            }
        }

        // 10. Smoke test de la prod
        stage('Smoke test prod') {
            when { expression { (!isRollback() && isMainBranch()) || (isRollback() && params.ROLLBACK_ENV == 'prod') } }
            steps {
                sh '''
                    curl -fsS --retry 15 --retry-delay 4 --retry-all-errors \
                      http://tasks-prod-api:8080/actuator/health | tee health.json
                    grep -q '"status":"UP"' health.json
                    curl -fsS http://tasks-prod-api:8080/actuator/info | tee info.json
                    grep -q "$TAG" info.json
                '''
            }
        }
    }

    post {
        success {
            echo "SUCCÈS : ${env.IMAGE_NAME}:${env.TAG} — staging : http://localhost:8081 | prod : http://localhost:8082"
        }
        failure {
            echo "ÉCHEC : lisez le premier stage rouge, puis son log (Console Output) et l'onglet Test Result."
        }
        aborted {
            echo 'Build interrompu (approbation refusée ou délai dépassé).'
        }
        always {
            cleanWs()   // workspace propre pour le prochain build (le cache Maven, lui, est conservé)
        }
    }
}
