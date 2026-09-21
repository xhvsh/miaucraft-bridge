# Dev-only: serves bridge\remote-config.json over HTTP so the local plugin can
# fetch its remote config without pushing to GitHub.
param(
  [int]$Port = 8099
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "remote-config-server.js"
if (-not (Test-Path -LiteralPath $script)) { throw "missing $script" }

Write-Host "Serving remote-config.json on http://127.0.0.1:$Port/remote-config.json"
& node $script
