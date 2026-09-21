# Downloads a Paper server into bridge/dev/server and prepares it for local
# testing of the bridge plugin. Safe to re-run.
param(
  [string]$MinecraftVersion = "1.21.11",
  [string]$ServerDir = (Join-Path $PSScriptRoot "server")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ServerDir)) {
  New-Item -ItemType Directory -Path $ServerDir | Out-Null
}

Write-Host "Resolving latest Paper build for $MinecraftVersion..."
$build = Invoke-RestMethod "https://fill.papermc.io/v3/projects/paper/versions/$MinecraftVersion/builds/latest"
$download = $build.downloads.'server:default'
$jarName = $download.name
$url = $download.url
$jarPath = Join-Path $ServerDir $jarName

if (-not (Test-Path -LiteralPath $jarPath)) {
  Write-Host "Downloading $jarName..."
  Invoke-WebRequest -Uri $url -OutFile $jarPath
} else {
  Write-Host "$jarName already present."
}

"eula=true" | Set-Content -LiteralPath (Join-Path $ServerDir "eula.txt")

$props = @(
  "online-mode=false",
  "white-list=true",
  "enforce-whitelist=false",
  "spawn-protection=0",
  "max-players=20",
  "motd=Miaucraft local test",
  "level-type=flat"
)
Set-Content -LiteralPath (Join-Path $ServerDir "server.properties") -Value $props

Write-Host "Paper $MinecraftVersion build $build ready in $ServerDir"
Write-Host "Next: run deploy-plugin.ps1, then start-server.ps1"
