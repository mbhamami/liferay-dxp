#!/usr/bin/env bash
#
# Build le module OSGi oidc-claims-logger sans installer Java/Gradle en local
# (utilise un container gradle:8.5-jdk17 jetable), puis copie le .jar
# directement dans le container Liferay via "docker cp".
#
# Avant le build, on copie les APIs Liferay depuis le container Liferay vers
# ./libs : on compile ainsi contre les jars RÉELS du runtime, ce qui garantit
# des plages d'Import-Package OSGi correctes (package OpenID Connect v8.0,
# jakarta.servlet, etc. sur Liferay 2025.q3.0).
#
# Pourquoi pas via le volume ./liferay/deploy ?
#  Sur les bind-mounts Windows/WSL, l'autodeploy de Liferay n'arrive pas à
#  supprimer le jar après traitement ("Unable to write ..."). docker cp évite
#  ce problème en posant le jar directement dans /opt/liferay/osgi/modules/.
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
LIFERAY_CONTAINER="${LIFERAY_CONTAINER:-liferay}"

# --- Récupère les APIs Liferay du runtime pour compiler contre les bonnes versions ---
LIBS_DIR="$MODULE_DIR/libs"
mkdir -p "$LIBS_DIR"

echo ">> Copie des APIs Liferay depuis le container..."
docker cp "$LIFERAY_CONTAINER:/opt/liferay/tomcat/webapps/ROOT/WEB-INF/shielded-container-lib/portal-kernel.jar" "$LIBS_DIR/portal-kernel.jar"
docker cp "$LIFERAY_CONTAINER:/opt/liferay/osgi/portal/com.liferay.portal.security.sso.openid.connect.api.jar" "$LIBS_DIR/oidc-connect-api.jar"

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
echo "   docker compose logs -f liferay | grep -i 'oidc\\.claims\\.logger'"
