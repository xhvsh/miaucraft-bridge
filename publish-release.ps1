# Publishes a plugin build as the GitHub release artifact.
#
#   powershell -File bridge/publish-release.ps1 -Version 2.2.0
#
# Bumps pom.xml to the version, builds, copies the jar to bridge/releases/,
# and writes bridge/plugin-update.json pointing at the raw GitHub URL.
#
# Commit and push these paths afterwards so deployed servers can fetch this
# exact version (their self-updater checks .../bridge/plugin-update.json):
#   bridge/plugin-update.json
#   bridge/releases/miaucraft-bridge-plugin-<version>.jar
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$BaseUrl = "https://raw.githubusercontent.com/xhvsh/miaucraft/main/bridge"
)

$ErrorActionPreference = "Stop"

# PowerShell 5.1's Set-Content -Encoding UTF8 emits a BOM, which some JSON
# parsers reject. Write UTF-8 without a BOM instead.
function WriteNoBom([string]$Path, [string]$Text) {
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

$scriptRoot = $PSScriptRoot
$pluginRoot = (Resolve-Path (Join-Path $scriptRoot "plugin\miaucraft-bridge-plugin")).Path
$pom = Join-Path $pluginRoot "pom.xml"

$pomText = Get-Content -LiteralPath $pom -Raw
$pattern = '(<artifactId>miaucraft-bridge-plugin</artifactId>\s*<version>)[^<]+(</version>)'
if ($pomText -notmatch $pattern) { throw "Could not find the project version in pom.xml" }
$newPom = [regex]::Replace($pomText, $pattern, '${1}' + $Version + '${2}')
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

$releasesDir = Join-Path $scriptRoot "releases"
New-Item -ItemType Directory -Force -Path $releasesDir | Out-Null
$name = "miaucraft-bridge-plugin-$Version.jar"
$dest = Join-Path $releasesDir $name
Copy-Item -LiteralPath $built -Destination $dest -Force

$sha = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLower()
$manifest = [ordered]@{
  version = $Version
  url     = "$BaseUrl/releases/$name"
  sha256  = $sha
  notes   = "Release $Version"
}
$json = $manifest | ConvertTo-Json
WriteNoBom -Path (Join-Path $scriptRoot "plugin-update.json") -Text $json

Write-Host ""
Write-Host "Released $name ($sha)"
Write-Host "Manifest: $(Join-Path $scriptRoot 'plugin-update.json')"
Write-Host ""
Write-Host "Next: commit and push these paths so servers can fetch it:"
Write-Host "  bridge/plugin-update.json"
Write-Host "  bridge/releases/$name"