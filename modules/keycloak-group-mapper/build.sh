#!/usr/bin/env bash
#
# Build le module OSGi keycloak-group-mapper sans installer Java/Gradle en local
# (utilise un container gradle:8.5-jdk17 jetable), puis copie le .jar
# directement dans le container Liferay via "docker cp".
#
# Pourquoi pas via le volume ./liferay/deploy ?
#  Sur les bind-mounts Windows/WSL, l'autodeploy de Liferay n'arrive pas à
#  supprimer le jar après traitement ("Unable to write ..."). docker cp évite
#  ce problème en posant le jar directement dans /opt/liferay/osgi/modules/.
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
LIFERAY_CONTAINER="${LIFERAY_CONTAINER:-liferay}"

echo ">> Build via container gradle:8.5-jdk17..."
docker run --rm \
    -v "$MODULE_DIR":/work \
    -v "$HOME/.gradle":/home/gradle/.gradle \
    -w /work \
    gradle:8.5-jdk17 gradle --no-daemon clean build

JAR_PATH=$(ls "$MODULE_DIR"/build/libs/*.jar | head -n 1)
JAR_NAME=$(basename "$JAR_PATH")

echo ">> Copie de $JAR_NAME vers $LIFERAY_CONTAINER:/opt/liferay/osgi/modules/"
docker cp "$JAR_PATH" "$LIFERAY_CONTAINER:/opt/liferay/osgi/modules/$JAR_NAME"

echo ">> Fait. Liferay détectera le bundle dans 5-10 s :"
echo "   docker compose logs -f liferay | grep -i 'keycloak\\.group\\.mapper'"
