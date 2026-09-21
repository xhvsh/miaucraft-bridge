# Starts the local Supabase stack (Postgres + Auth + PostgREST + Realtime) that
# the bridge plugin and the website talk to during local testing.
#
# Requires Docker Desktop (running) and the Supabase CLI (either installed
# globally or available via npx). The local project lives in bridge/dev/supabase.
param(
  [string]$DevDir = $PSScriptRoot,
  [switch]$Reset
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "Docker is not installed or not on PATH. Install Docker Desktop, start it, then re-run."
}
$prev = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& docker info *> $null
$dockerOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prev
if (-not $dockerOk) {
  throw "The Docker daemon is not running. Start Docker Desktop, then re-run."
}

function Invoke-SupabaseCli {
  param([string[]]$CliArgs)
  if (Get-Command supabase -ErrorAction SilentlyContinue) {
    & supabase @CliArgs
  } elseif (Get-Command npx -ErrorAction SilentlyContinue) {
    & npx --yes supabase @CliArgs
  } else {
    throw "Supabase CLI not found. Install it with 'npm i -g supabase' or make npx available."
  }
}

Push-Location $DevDir
try {
  $configToml = Join-Path $DevDir "supabase\config.toml"
  if (-not (Test-Path -LiteralPath $configToml)) {
    Write-Host "Initializing local Supabase project..."
    Invoke-SupabaseCli @("init")
    if (-not (Test-Path -LiteralPath $configToml)) {
      throw "supabase init did not produce supabase/config.toml"
    }
  }
  if ($Reset) {
    Write-Host "Resetting local database and applying migrations..."
    Invoke-SupabaseCli @("db", "reset")
  }
  Write-Host "Starting local Supabase..."
  Invoke-SupabaseCli @("start")
  Write-Host ""
  Write-Host "Copy the API URL and service_role key above into:"
  Write-Host "  bridge\dev\server\plugins\MiaucraftBridge\config.yml"
  Write-Host "  (supabase.url = http://127.0.0.1:54321, supabase.service-role-key = service_role)"
  Invoke-SupabaseCli @("status")
} finally {
  Pop-Location
}
