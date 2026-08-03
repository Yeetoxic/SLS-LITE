param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 25565,
    [string]$Version = "1.21.5",
    [string]$Registry = "minigame",
    [string]$Blueprint = "stage1_lifecycle",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$clientDirectory = Join-Path $repository "tools\protocol-smoke"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required to run the matchmaking smoke client."
}
if (-not (Test-Path -LiteralPath (Join-Path $clientDirectory "node_modules"))) {
    Push-Location $clientDirectory
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to install the pinned protocol smoke dependency."
        }
    } finally {
        Pop-Location
    }
}

& node (Join-Path $clientDirectory "matchmaking-smoke.js") `
    --host $HostName `
    --port $Port `
    --version $Version `
    --registry $Registry `
    --blueprint $Blueprint `
    --timeout-seconds $TimeoutSeconds
if ($LASTEXITCODE -ne 0) {
    throw "The Pterodactyl matchmaking scenario failed."
}
