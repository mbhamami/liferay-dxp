# Build le module OSGi oidc-claims-logger via un container Gradle jetable,
# puis copie le .jar directement dans le container Liferay via "docker cp".
#
# Avant le build, on copie les APIs Liferay depuis le container Liferay vers
# ./libs : on compile ainsi contre les jars REELS du runtime, ce qui garantit
# des plages d'Import-Package OSGi correctes (package OpenID Connect v8.0,
# jakarta.servlet, etc. sur Liferay 2025.q3.0).
#
# Pourquoi pas via le volume .\liferay\deploy ?
#   Sur les bind-mounts Windows/WSL, l'autodeploy de Liferay n'arrive pas a
#   supprimer le jar apres traitement ("Unable to write ..."). docker cp
#   evite ce probleme en posant le jar directement dans
#   /opt/liferay/osgi/modules/.

$ErrorActionPreference = 'Stop'

$ModuleDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LiferayContainer = if ($env:LIFERAY_CONTAINER) { $env:LIFERAY_CONTAINER } else { 'liferay' }

# --- Recupere les APIs Liferay du runtime pour compiler contre les bonnes versions ---
$LibsDir = Join-Path $ModuleDir 'libs'
New-Item -ItemType Directory -Force -Path $LibsDir | Out-Null

Write-Host '>> Copie des APIs Liferay depuis le container...'
docker cp "${LiferayContainer}:/opt/liferay/tomcat/webapps/ROOT/WEB-INF/shielded-container-lib/portal-kernel.jar" (Join-Path $LibsDir 'portal-kernel.jar')
if ($LASTEXITCODE -ne 0) { throw 'docker cp portal-kernel.jar failed' }
docker cp "${LiferayContainer}:/opt/liferay/osgi/portal/com.liferay.portal.security.sso.openid.connect.api.jar" (Join-Path $LibsDir 'oidc-connect-api.jar')
if ($LASTEXITCODE -ne 0) { throw 'docker cp oidc-connect-api.jar failed' }

Write-Host '>> Build via container gradle:8.5-jdk17...'
docker run --rm `
    -v "${ModuleDir}:/work" `
    -w /work `
    gradle:8.5-jdk17 gradle --no-daemon clean build
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$JarFile = Get-ChildItem -Path (Join-Path $ModuleDir 'build\libs') -Filter '*.jar' | Select-Object -First 1
if (-not $JarFile) { throw 'No jar produced by Gradle' }

Write-Host ">> Copie de $($JarFile.Name) vers ${LiferayContainer}:/opt/liferay/osgi/modules/"
docker cp $JarFile.FullName "${LiferayContainer}:/opt/liferay/osgi/modules/$($JarFile.Name)"
if ($LASTEXITCODE -ne 0) { throw 'docker cp failed' }

Write-Host '>> Fait. Liferay detectera le bundle dans 5-10 s :'
Write-Host "   docker compose logs -f liferay | Select-String 'oidc\.claims\.logger'"
