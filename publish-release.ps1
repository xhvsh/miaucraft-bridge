# Publishes a plugin build as the GitHub release artifact.
#
#   powershell -File publish-release.ps1 -Version 2.4.3
#   powershell -File publish-release.ps1 -Version 2.4.3 -SiteRepo C:\path\to\miaucraft
#
# Bumps pom.xml to the version, builds (tests included), copies the jar to
# releases/, and writes plugin-update.json pointing at the raw GitHub URL.
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
  [string]$SiteRepo = "",
  [switch]$NoTests
)

$ErrorActionPreference = "Stop"

# PowerShell 5.1's Set-Content -Encoding UTF8 emits a BOM, which some JSON
# parsers reject. Write UTF-8 without a BOM instead.
function WriteNoBom([string]$Path, [string]$Text) {
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

if ($Version -notmatch '^\d+(\.\d+)*$') {
  throw "Version '$Version' is not a dotted numeric release (e.g. 2.4.3)."
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
  # Tests run here on purpose: the release script is the only place a broken
  # build would otherwise be published from.
  #
  # Maven, and the log output of the tests it runs, write to stderr routinely
  # without failing anything. With $ErrorActionPreference = "Stop" that aborts
  # the release (PowerShell turns native stderr into a terminating error), so
  # the build runs through Start-Process with redirected streams and only the
  # exit code decides whether it succeeded.
  $mvnArgs = @("-B", "-q")
  if ($NoTests) { $mvnArgs += "-DskipTests" }
  $mvnArgs += "package"
  $mvnOutLog = Join-Path ([System.IO.Path]::GetTempPath()) ("bridge-mvn-" + [System.IO.Path]::GetRandomFileName())
  $mvnErrLog = "$mvnOutLog.err"
  $mvn = if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) { "mvn.cmd" } else { "mvn" }
  $proc = Start-Process -FilePath $mvn -ArgumentList $mvnArgs -WorkingDirectory $pluginRoot `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput $mvnOutLog -RedirectStandardError $mvnErrLog
  $mvnExit = $proc.ExitCode
  if ($mvnExit -ne 0) {
    Get-Content -LiteralPath $mvnOutLog, $mvnErrLog -ErrorAction SilentlyContinue |
      ForEach-Object { Write-Host "$_" }
    # Leave the tree as it was found: a failed release must not leave pom.xml
    # bumped to a version that was never published.
    WriteNoBom -Path $pom -Text $pomText
    throw "Maven build failed (exit $mvnExit); pom.xml restored to the previous version."
  }
  Remove-Item -LiteralPath $mvnOutLog, $mvnErrLog -Force -ErrorAction SilentlyContinue
} finally {
  Pop-Location
}

$built = Join-Path $pluginRoot "target\miaucraft-bridge-plugin.jar"
if (-not (Test-Path -LiteralPath $built)) { throw "Build output not found at $built" }

# The self-updater reads plugin.yml from inside the jar and refuses a jar whose
# version does not match the manifest, so check the built artifact here instead
# of failing in every server's log.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($built)
try {
  $entry = $zip.GetEntry("plugin.yml")
  if ($null -eq $entry) { throw "Built jar has no plugin.yml" }
  $reader = New-Object System.IO.StreamReader($entry.Open())
  try { $jarPluginYml = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally {
  $zip.Dispose()
}
$versionPattern = '(?m)^version:\s*["'']?([^"''\r\n]+)["'']?\s*$'
if ($jarPluginYml -notmatch $versionPattern) { throw "Built jar's plugin.yml has no version" }
if ($Matches[1].Trim() -ne $Version) {
  throw ("Built jar's plugin.yml says '$($Matches[1].Trim())' but -Version is '$Version'.")
}

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

# Fail loudly here rather than in every server's log: the updater refuses a
# manifest without a digest, so shipping a malformed one bricks auto-update.
$written = Get-Content -LiteralPath (Join-Path $scriptRoot "plugin-update.json") -Raw | ConvertFrom-Json
if ($written.version -ne $Version) { throw "plugin-update.json has the wrong version" }
if ($written.sha256 -ne $sha) { throw "plugin-update.json has the wrong sha256" }
if (-not $written.url.StartsWith("https://")) { throw "plugin-update.json url must be https" }

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
  Write-Host ('  git add bridge && git commit -m "shim v{0}" && git push' -f $Version)
}
