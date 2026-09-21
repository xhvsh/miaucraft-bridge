# Publishes a new plugin build to the local dev "remote" (served by
# remote-config-server.js) so the running server can self-update to it.
#
#   powershell -File bridge/dev/publish-update.ps1 -Version 2.1.0
#
# Bumps pom.xml to the given version, builds, copies the jar to
# bridge/dev/jars/, and writes bridge/dev/plugin-update.json with its sha256.
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$BaseUrl = "http://127.0.0.1:8099"
)

$ErrorActionPreference = "Stop"

# PowerShell 5.1's Set-Content -Encoding UTF8 emits a BOM, which some JSON
# parsers reject. Write UTF-8 without a BOM instead.
function WriteNoBom([string]$Path, [string]$Text) {
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

$devDir = $PSScriptRoot
$bridgeDir = (Resolve-Path (Join-Path $devDir "..")).Path
$pluginRoot = (Resolve-Path (Join-Path $devDir "..\plugin\miaucraft-bridge-plugin")).Path
$pom = Join-Path $pluginRoot "pom.xml"

$pomText = Get-Content -LiteralPath $pom -Raw
$pattern = '(<artifactId>miaucraft-bridge-plugin</artifactId>\s*<version>)[^<]+(</version>)'
$replacement = '${1}' + $Version + '${2}'
if ($pomText -notmatch $pattern) { throw "Could not find the project version in pom.xml" }
$newPom = [regex]::Replace($pomText, $pattern, $replacement)
WriteNoBom -Path $pom -Text $newPom
Write-Host "pom.xml -> $Version"

Push-Location $pluginRoot
try {
  & mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
} finally {
  Pop-Location
}

$built = Join-Path $pluginRoot "target\miaucraft-bridge-plugin.jar"
if (-not (Test-Path -LiteralPath $built)) { throw "Build output not found at $built" }

$jarsDir = Join-Path $devDir "jars"
New-Item -ItemType Directory -Force -Path $jarsDir | Out-Null
$name = "miaucraft-bridge-plugin-$Version.jar"
$dest = Join-Path $jarsDir $name
Copy-Item -LiteralPath $built -Destination $dest -Force

$sha = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLower()
$manifest = [ordered]@{
  version = $Version
  url     = "$BaseUrl/jars/$name"
  sha256  = $sha
  notes   = "Local dev build $Version"
}
$json = $manifest | ConvertTo-Json
WriteNoBom -Path (Join-Path $devDir "plugin-update.json") -Text $json

Write-Host "Published $name ($sha)"
Write-Host "Manifest: $(Join-Path $devDir 'plugin-update.json')"
Write-Host "The running plugin will now see v$Version via /bridge update."
