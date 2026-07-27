param(
    [switch]$SkipDockerConfig
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location $projectRoot
try {
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -ne $maven) {
        & mvn --batch-mode --no-transfer-progress clean verify
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    else {
        Write-Warning "Maven is not installed; run 'docker build --target test .' instead."
    }

    if (-not $SkipDockerConfig) {
        docker compose config --quiet
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}
finally {
    Pop-Location
}
