# Stops the local Supabase stack started by start-db.ps1.
param(
  [string]$DevDir = $PSScriptRoot
)

$ErrorActionPreference = "Stop"

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
  Invoke-SupabaseCli @("stop")
} finally {
  Pop-Location
}
