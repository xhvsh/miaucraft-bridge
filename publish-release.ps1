# Publishes a plugin build as the GitHub release artifact.
#
#   powershell -File publish-release.ps1 -Version 2.4.3
#   powershell -File publish-release.ps1 -Version 2.4.3 -SiteRepo C:\path\to\miaucraft
#
# Bumps pom.xml to the version, builds, copies the jar to releases/,
# and writes plugin-update.json pointing at the raw GitHub URL.
#
# Commit and push these paths afterwards so deployed servers can fetch this
# exact version (their self-updater checks .../plugin-update.json):
#   plugin-update.json
#   releases/miaucraft-bridge-plugin-<version>.jar
#
# Pass -SiteRepo to also mirror plugin-update.json + remote-config.json +
# remote-config.schema.json into that repo's bridge/ (newer servers poll the
# new repo directly; already-deployed ones must keep polling the old path,
# so the shim there must be bumped on every release).
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$BaseUrl = "https://raw.githubusercontent.com/xhvsh/miaucraft-bridge/main",
  [string]$SiteRepo = ""
)

$ErrorActionPreference = "Stop"

# PowerShell 5.1's Set-Content -Encoding UTF8 emits a BOM, which some JSON
# parsers reject. Write UTF-8 without a BOM instead.
function WriteNoBom([string]$Path, [string]$Text) {
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

$scriptRoot = $PSScriptRoot
$pluginRoot = $scriptRoot
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
Write-Host "  plugin-update.json"
Write-Host "  releases/$name"

if ($SiteRepo -ne "") {
  $shimDir = Join-Path $SiteRepo "bridge"
  if (-not (Test-Path -LiteralPath $shimDir)) { throw "Site repo bridge/ not found at $shimDir" }
  foreach ($f in @("plugin-update.json", "remote-config.json", "remote-config.schema.json")) {
    Copy-Item -LiteralPath (Join-Path $scriptRoot $f) -Destination (Join-Path $shimDir $f) -Force
  }
  Write-Host ""
  Write-Host "Mirrored the 3 shim files into $shimDir. Commit and push there so"
  Write-Host "already-deployed servers (which still poll the old repo path) see this update:"
  Write-Host "  cd $SiteRepo"
  Write-Host "  git add bridge && git commit -m \"shim v$Version\" && git push"