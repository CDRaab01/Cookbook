<#
.SYNOPSIS
  Redeploy the Cookbook server from the canonical deployment clone (Windows / Docker Desktop).

.DESCRIPTION
  Pulls the requested ref, rebuilds the server image, restarts the stack, and waits
  for the API to report healthy. Idempotent and safe to re-run.

  This is the single source of redeploy logic — both the Deploy GitHub Actions
  workflow (.github/workflows/deploy.yml) and a human at the keyboard call it, so
  automated and manual deploys behave identically.

  The script resolves its own location to find the repo root, so it operates on the
  real deployment clone (which owns server/.env and the pgdata volume), never on a
  runner's ephemeral checkout. .env is gitignored and pgdata is a Docker named
  volume, so neither is touched by `git reset --hard`.

  Database migrations run automatically when the container starts
  (server/docker-entrypoint.sh -> alembic upgrade head); no separate step here.

.PARAMETER Ref
  Commit SHA or branch to deploy. Defaults to origin/main. Pass a prior SHA to roll back.

.PARAMETER HealthUrl
  Health endpoint to poll after restart. Defaults to http://127.0.0.1:8003/health
  (Cookbook is published on 8003: Spotter owns 8000, Plate 8001, and posterizarr 8002).

.PARAMETER TimeoutSeconds
  How long to wait for the health check before failing. Defaults to 120.

.PARAMETER FailureLogLines
  Container log tail dumped on health-gate failure (so an unattended deploy is
  debuggable from the run output). Defaults to 100.

.EXAMPLE
  powershell deploy/redeploy.ps1

.EXAMPLE
  powershell deploy/redeploy.ps1 -Ref 1a2b3c4   # roll back to a prior commit
#>
[CmdletBinding()]
param(
  [string]$Ref = "origin/main",
  [string]$HealthUrl = "http://127.0.0.1:8003/health",
  [int]$TimeoutSeconds = 120,
  [int]$FailureLogLines = 100
)

$ErrorActionPreference = "Stop"

# Repo root = parent of this script's directory (deploy/).
$RepoDir = Split-Path -Parent $PSScriptRoot

# $ArgList (not $Args — that's an automatic variable) so splatting is unambiguous
# under both Windows PowerShell 5.1 and PowerShell 7.
function Invoke-Checked {
  param([string]$Exe, [string[]]$ArgList)
  Write-Host "> $Exe $($ArgList -join ' ')"
  & $Exe @ArgList
  if ($LASTEXITCODE -ne 0) {
    throw "Command failed ($LASTEXITCODE): $Exe $($ArgList -join ' ')"
  }
}

Write-Host "=== Cookbook redeploy ==="
Write-Host "Repo:   $RepoDir"
Write-Host "Ref:    $Ref"

# 0. Git refuses to operate on a repo owned by a different account than the one
#    running it (CVE-2022-24765 mitigation) -- exactly the case when a Windows
#    service account (e.g. NetworkService, running the self-hosted runner)
#    redeploys a clone owned by an interactive user. --global (not --system) so
#    this self-heals under whichever account runs the script, with no admin step.
& git config --global --add safe.directory $RepoDir 2>$null

# 1. Fetch latest refs.
Invoke-Checked git @("-C", $RepoDir, "fetch", "--prune", "origin")

# 2. Check out the exact ref (clean, reproducible deploy).
Invoke-Checked git @("-C", $RepoDir, "reset", "--hard", $Ref)
$deployedSha = (& git -C $RepoDir rev-parse --short HEAD).Trim()
Write-Host "Deployed commit: $deployedSha"

# 3. Rebuild + restart. Migrations run on container boot via the entrypoint.
#    Stamp the build so GET /version reports what's actually running.
$env:GIT_SHA = $deployedSha
$env:BUILT_AT = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
Invoke-Checked docker @("compose", "--project-directory", $RepoDir, "up", "-d", "--build", "--remove-orphans")

# 4. Health gate — fail the run if the API doesn't come back healthy.
Write-Host "Waiting for $HealthUrl (timeout ${TimeoutSeconds}s)..."
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthy = $false
while ((Get-Date) -lt $deadline) {
  try {
    $resp = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 5
    if ($resp.status -eq "ok") { $healthy = $true; break }
  } catch {
    # not up yet
  }
  Start-Sleep -Seconds 3
}
if (-not $healthy) {
  # Dump recent container logs so a failed deploy is debuggable from the run output
  # (the runner is unattended; without this the failure is opaque).
  Write-Host "--- docker compose logs (last ${FailureLogLines} lines) ---"
  & docker compose --project-directory $RepoDir logs --no-color --tail $FailureLogLines 2>$null
  throw "Health check failed: $HealthUrl did not report ok within ${TimeoutSeconds}s."
}
Write-Host "Health check passed."

# 4b. Report tunnel readiness when the tunnel profile is active (informational --
#     /health only proves the API is up locally, not that Cloudflare can reach it).
$cfId = (& docker compose --project-directory $RepoDir ps -q cloudflared 2>$null)
if ($cfId) {
  $cfHealth = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}no healthcheck{{end}}' $cfId 2>$null)
  Write-Host "Tunnel (cloudflared) health: $cfHealth"
  if ($cfHealth -eq "unhealthy") {
    Write-Warning "cloudflared reports unhealthy -- the public hostname may be returning 530s."
  }
}

# 5. Reclaim disk from superseded image layers.
Invoke-Checked docker @("image", "prune", "-f")

Write-Host "=== Redeploy complete ($deployedSha) ==="
