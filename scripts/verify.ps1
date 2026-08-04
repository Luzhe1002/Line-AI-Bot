param(
    [switch]$SkipDockerConfig,
    [switch]$SkipUiE2E
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
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if ($null -eq $docker) {
            throw "Neither Maven nor Docker is installed; Java verification cannot run."
        }
        & docker build --target test --tag line-ai-bot-java-test:verify .
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    $npm = Get-Command npm -ErrorAction SilentlyContinue
    if ($null -eq $npm) {
        throw "npm is required for frontend verification."
    }
    & npm run test:ui:logic
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (-not $SkipUiE2E) {
        & npm run test:ui:e2e
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    if (-not $SkipDockerConfig) {
        if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
            throw "Docker is required unless -SkipDockerConfig is supplied."
        }
        docker compose config --quiet
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}
finally {
    Pop-Location
}
