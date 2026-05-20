#!/usr/bin/env bash
#
# Build le module OSGi keycloak-group-mapper sans installer Java/Gradle en local
# (utilise un container gradle:8.5-jdk11 jetable), puis copie le .jar dans
# ../../liferay/deploy/ pour hot-deploy par Liferay.
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$(cd "$MODULE_DIR/../../liferay/deploy" && pwd)"

echo ">> Build via container gradle:8.5-jdk11..."
docker run --rm \
    -v "$MODULE_DIR":/work \
    -v "$HOME/.gradle":/home/gradle/.gradle \
    -w /work \
    gradle:8.5-jdk11 gradle --no-daemon clean build

echo ">> Copie du jar vers $DEPLOY_DIR"
cp "$MODULE_DIR"/build/libs/*.jar "$DEPLOY_DIR/"

echo ">> Fait. Liferay détectera le bundle dans 5-10 s :"
echo "   docker compose logs -f liferay | grep -i 'keycloak\\.group\\.mapper'"
