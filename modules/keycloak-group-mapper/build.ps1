# Build le module OSGi keycloak-group-mapper via un container Gradle jetable,
# puis copie le .jar directement dans le container Liferay via "docker cp".
#
# Pourquoi pas via le volume .\liferay\deploy ?
#   Sur les bind-mounts Windows/WSL, l'autodeploy de Liferay n'arrive pas a
#   supprimer le jar apres traitement ("Unable to write ..."). docker cp
#   evite ce probleme en posant le jar directement dans
#   /opt/liferay/osgi/modules/.

$ErrorActionPreference = 'Stop'

$ModuleDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LiferayContainer = if ($env:LIFERAY_CONTAINER) { $env:LIFERAY_CONTAINER } else { 'liferay' }

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
Write-Host "   docker compose logs -f liferay | Select-String 'keycloak\.group\.mapper'"
