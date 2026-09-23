param([string]$Title = "Release main to production")
$ErrorActionPreference = "Stop"
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "Install GitHub CLI and run gh auth login first." }
$repo = "Luzhe1002/Line-AI-Bot"
$existing = & gh pr list --repo $repo --base production --head main --state open --json url --jq '.[0].url'
if ($LASTEXITCODE -ne 0) { throw "Could not inspect existing release PRs." }
if ($existing) { Write-Output $existing; exit 0 }
$comparison = & gh api "repos/$repo/compare/production...main" --jq '.ahead_by'
if ($LASTEXITCODE -ne 0) { throw "Could not compare release branches." }
if ([int]$comparison -eq 0) { throw "main has no new release commits." }
$body = @"
Promote the current main branch to production.

Before merging, confirm the verify check passes, review migrations against the deployed Flyway history, and confirm the Render service tracks production with checksPass.

Record the released commit and deployment result after merging. Use a merge commit to retain shared main/production history. Do not squash or rebase release PRs.
"@
$bodyFile = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($bodyFile, $body)
    & gh pr create --repo $repo --base production --head main --title $Title --body-file $bodyFile
    if ($LASTEXITCODE -ne 0) { throw "Release PR creation failed." }
} finally { Remove-Item -LiteralPath $bodyFile }
