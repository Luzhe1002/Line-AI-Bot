param(
    [switch]$SkipCompose
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project virtual environment not found: $python"
}

Push-Location $projectRoot
try {
    & $python -m ruff check alembic src tests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $python -m ruff format --check alembic src tests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $python -m pytest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $python -m alembic check
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (-not $SkipCompose) {
        docker compose config --quiet
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}
finally {
    Pop-Location
}
