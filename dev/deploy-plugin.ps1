# Builds the bridge plugin and copies the jar into the local test server.
param(
  [string]$ServerDir = (Join-Path $PSScriptRoot "server")
)

$ErrorActionPreference = "Stop"

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\plugin\miaucraft-bridge-plugin")).Path

Push-Location $pluginRoot
try {
  & mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
} finally {
  Pop-Location
}

$src = Join-Path $pluginRoot "target\miaucraft-bridge-plugin.jar"
if (-not (Test-Path -LiteralPath $src)) { throw "Build output not found at $src" }

$pluginsDir = Join-Path $ServerDir "plugins"
New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
Copy-Item -LiteralPath $src -Destination $pluginsDir -Force

Write-Host "Deployed to $pluginsDir"
Write-Host "If this is the first run, start the server once to generate plugins\MiaucraftBridge\config.yml,"
Write-Host "then fill in supabase.url and supabase.service-role-key and start it again."
