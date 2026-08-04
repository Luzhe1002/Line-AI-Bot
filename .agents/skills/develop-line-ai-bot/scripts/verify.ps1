param(
    [switch]$SkipCompose,
    [switch]$SkipUiE2E
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
$verificationScript = Join-Path $projectRoot "scripts\verify.ps1"

Push-Location $projectRoot
try {
    & $verificationScript -SkipDockerConfig:$SkipCompose -SkipUiE2E:$SkipUiE2E
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}
