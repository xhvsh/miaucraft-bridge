# Starts the local Paper test server (bridge/dev/server).
param(
  [string]$ServerDir = (Join-Path $PSScriptRoot "server"),
  [int]$MemoryMb = 2048,
  [string]$JavaExe = ""
)

$ErrorActionPreference = "Stop"

if (-not $JavaExe) {
  # Prefer a Java 21 install; fall back to whatever "java" resolves to.
  $candidates = @(
    "$env:ProgramFiles\Eclipse Adoptium\jdk-21*\bin\java.exe",
    "$env:ProgramFiles\Java\jdk-21*\bin\java.exe",
    "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-21*\bin\java.exe"
  )
  foreach ($pattern in $candidates) {
    $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $JavaExe = $found.FullName; break }
  }
  if (-not $JavaExe) { $JavaExe = "java" }
}

$jar = Get-ChildItem -LiteralPath $ServerDir -Filter "paper-*.jar" -ErrorAction SilentlyContinue |
  Sort-Object Name -Descending | Select-Object -First 1
if (-not $jar) { throw "No Paper jar in $ServerDir. Run setup-server.ps1 first." }

Write-Host "Starting $($jar.Name) with $JavaExe (${MemoryMb}M)..."
Push-Location $ServerDir
try {
  & $JavaExe "-Xmx${MemoryMb}M" -jar $jar.FullName nogui
} finally {
  Pop-Location
}
