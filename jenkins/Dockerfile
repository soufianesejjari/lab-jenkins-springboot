# =============================================================================
# Jenkins du lab : image officielle LTS (JDK 21) + CLI Docker + plugins + JCasC
# =============================================================================
FROM jenkins/jenkins:lts-jdk21

USER root

# CLI Docker + plugins buildx et compose (le démon, lui, est celui de l'hôte, via le socket)
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin docker-compose-plugin \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# LAB UNIQUEMENT : le dépôt /repo (monté depuis l'hôte) appartient à un autre utilisateur ;
# sans cette ligne Git refuse de le lire ("detected dubious ownership").
RUN git config --system --add safe.directory '*'

USER jenkins

# Plugins (dernière version compatible avec le core de l'image ; en production, épinglez nom:version)
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Configuration as Code
COPY --chown=jenkins:jenkins casc.yaml /var/jenkins_casc/casc.yaml
ENV CASC_JENKINS_CONFIG=/var/jenkins_casc/casc.yaml

# - pas d'assistant d'installation (tout est dans casc.yaml)
# - checkout local file:///repo autorisé (mode hors-ligne du lab)
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
