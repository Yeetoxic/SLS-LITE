param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 25565,
    [string[]]$Versions = @("1.21.5"),
    [string]$LobbyInstanceId,
    [string]$VelocityContainer,
    [string]$PanelContainer = "sls-ptero-panel",
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$clientDirectory = Join-Path $repository "tools\protocol-smoke"
$commandHelper = Join-Path $repository "infra\pterodactyl\send-command.php"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required to run the lobby handoff client."
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required to control the local Pterodactyl fixture."
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

if ([string]::IsNullOrWhiteSpace($VelocityContainer)) {
    $VelocityContainer = (& docker ps --filter "publish=$Port" --format "{{.Names}}" | Select-Object -First 1).Trim()
}
if ([string]::IsNullOrWhiteSpace($VelocityContainer)) {
    throw "Could not find the running Velocity fixture publishing port $Port."
}

$velocityLogs = & docker logs --tail 500 $VelocityContainer 2>&1 | Out-String
if ([string]::IsNullOrWhiteSpace($LobbyInstanceId)) {
    $matches = [regex]::Matches($velocityLogs, "Managed lobby ([a-z0-9._-]+) is ready")
    if ($matches.Count -eq 0) {
        throw "Could not discover a ready managed lobby in $VelocityContainer logs."
    }
    $LobbyInstanceId = $matches[$matches.Count - 1].Groups[1].Value
}

& docker cp $commandHelper "${PanelContainer}:/tmp/sls-lite-send-command.php"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to copy the bounded console-command helper into $PanelContainer."
}

foreach ($version in $Versions) {
    $suffix = [Guid]::NewGuid().ToString("N")
    $stdoutPath = Join-Path ([IO.Path]::GetTempPath()) "sls-handoff-$suffix.out"
    $stderrPath = Join-Path ([IO.Path]::GetTempPath()) "sls-handoff-$suffix.err"
    $username = ("SLSH_" + $version.Replace(".", "_")).Substring(0, [Math]::Min(16, 5 + $version.Length))
    $arguments = @(
        (Join-Path $clientDirectory "handoff-smoke.js"),
        "--host", $HostName,
        "--port", $Port,
        "--version", $version,
        "--username", $username,
        "--timeout-seconds", $TimeoutSeconds
    )
    $process = Start-Process node -ArgumentList $arguments -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    try {
        $readyDeadline = [DateTime]::UtcNow.AddSeconds([Math]::Min(30, $TimeoutSeconds))
        do {
            Start-Sleep -Milliseconds 250
            $output = if (Test-Path -LiteralPath $stdoutPath) {
                Get-Content -LiteralPath $stdoutPath -Raw
            } else { "" }
            if ($process.HasExited) {
                $earlyErrors = if (Test-Path -LiteralPath $stderrPath) {
                    Get-Content -LiteralPath $stderrPath -Raw
                } else { "" }
                throw "Handoff client exited before reaching the managed lobby.`n$output`n$earlyErrors"
            }
        } until (($output -match "(?m)^READY ") -or ([DateTime]::UtcNow -ge $readyDeadline))
        if ($output -notmatch "(?m)^READY ") {
            throw "Handoff client did not reach the managed lobby within the readiness deadline."
        }

        & docker exec -e PANEL_ROOT=/app $PanelContainer php /tmp/sls-lite-send-command.php `
            sls restart $LobbyInstanceId --force
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to send the forced managed-lobby restart command."
        }

        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw "Handoff client exceeded its completion deadline."
        }
        # Populate ExitCode reliably after asynchronous output redirection has
        # drained; the timed overload alone can leave the cached value unset.
        $process.WaitForExit()
        $process.Refresh()
        $output = Get-Content -LiteralPath $stdoutPath -Raw
        $errors = Get-Content -LiteralPath $stderrPath -Raw
        Write-Host $output.TrimEnd()
        if (-not [string]::IsNullOrWhiteSpace($errors)) {
            Write-Error $errors.TrimEnd()
        }
        $escapedVersion = [regex]::Escape($version)
        if ($output -notmatch "(?m)^PASS $escapedVersion\:") {
            throw "Lobby handoff client did not emit its completion marker for Minecraft $version."
        }
        # Windows PowerShell can leave ExitCode unset for a redirected process
        # even after it has exited. Treat a reported non-zero code as failure;
        # the explicit PASS marker above remains the portable completion gate.
        if ($null -ne $process.ExitCode -and $process.ExitCode -ne 0) {
            throw "Lobby handoff failed for Minecraft $version."
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "PASS: managed-lobby handoff completed for $($Versions -join ', ') via $LobbyInstanceId."
