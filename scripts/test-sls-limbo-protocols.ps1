param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 25565,
    [string[]]$Versions = @("1.21.4"),
    [string]$ExpectedBrand = "SLS-Limbo",
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$clientDirectory = Join-Path $repository "tools\protocol-smoke"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required to run the protocol smoke client."
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

$arguments = @(
    (Join-Path $clientDirectory "smoke.js"),
    "--host", $HostName,
    "--port", $Port,
    "--versions", ($Versions -join ","),
    "--brand", $ExpectedBrand,
    "--timeout-seconds", $TimeoutSeconds
)
& node @arguments
if ($LASTEXITCODE -ne 0) {
    throw "One or more SLS-Limbo protocol smoke checks failed."
}
