# Build le module OSGi keycloak-group-mapper via un container Gradle jetable,
# puis copie le .jar dans ..\..\liferay\deploy\ pour hot-deploy.

$ErrorActionPreference = 'Stop'

$ModuleDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DeployDir = Resolve-Path (Join-Path $ModuleDir '..\..\liferay\deploy')

Write-Host '>> Build via container gradle:8.5-jdk11...'
docker run --rm `
    -v "${ModuleDir}:/work" `
    -w /work `
    gradle:8.5-jdk11 gradle --no-daemon clean build
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

Write-Host ">> Copie du jar vers $DeployDir"
Copy-Item -Path (Join-Path $ModuleDir 'build\libs\*.jar') -Destination $DeployDir -Force

Write-Host '>> Fait. Liferay détectera le bundle dans 5-10 s :'
Write-Host "   docker compose logs -f liferay | Select-String 'keycloak\.group\.mapper'"
