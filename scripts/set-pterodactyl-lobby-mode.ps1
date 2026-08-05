param(
    [ValidateSet("velocity", "external", "managed")]
    [string]$Mode = "velocity",
    [string]$VelocityUuid = "c165ae9c-1f88-4460-967e-1c0193d074d4"
)

$ErrorActionPreference = "Stop"

$panelContainer = "sls-ptero-panel"
$wingsContainer = "sls-ptero-wings"
$velocityIdentifier = $VelocityUuid.Substring(0, 8)
$velocityContainer = (docker inspect $VelocityUuid | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or $null -eq $velocityContainer) {
    throw "Could not inspect the active Velocity allocation."
}
$velocityVolume = ($velocityContainer.Mounts | Where-Object Destination -eq "/home/container").Source
if ([string]::IsNullOrWhiteSpace($velocityVolume)) {
    throw "Could not resolve the active Velocity allocation mount."
}
if ($velocityVolume -notmatch '^/var/lib/docker/volumes/([^/]+)/_data/volumes/') {
    throw "Unsupported Velocity allocation mount: $velocityVolume"
}
$gameDataVolume = $Matches[1]
$volumeDataRoot = "/var/lib/docker/volumes/$gameDataVolume/_data"
$allocationRoot = $velocityVolume.Substring(0, $velocityVolume.LastIndexOf('/'))
$pluginData = "$velocityVolume/plugins/sls-lite"
$helper = Join-Path $PSScriptRoot "create-pterodactyl-paper-lobby-local.php"
$velocityHelper = Join-Path $PSScriptRoot "create-pterodactyl-velocity-local.php"

function Assert-LastExitCode([string]$Operation) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

function Invoke-PanelHelper([string]$LocalPath, [string]$ContainerName, [string[]]$Arguments) {
    $containerPath = "/tmp/$ContainerName"
    docker cp $LocalPath "${panelContainer}:$containerPath"
    Assert-LastExitCode "Copying $ContainerName into the Panel"
    $output = docker exec -e PANEL_ROOT=/app $panelContainer `
        php $containerPath @Arguments
    Assert-LastExitCode "Running $ContainerName"
    return ($output -join "`n") | ConvertFrom-Json
}

function Convert-ToUtilityPath([string]$AllocationPath) {
    if (-not $AllocationPath.StartsWith("$volumeDataRoot/")) {
        throw "Allocation path is outside the fixture volume: $AllocationPath"
    }
    return "/data/$($AllocationPath.Substring($volumeDataRoot.Length + 1))"
}

function Invoke-VolumeTool([string[]]$Arguments) {
    docker run --rm -v "${gameDataVolume}:/data" alpine:3.22 @Arguments
    Assert-LastExitCode "Running fixture-volume utility"
}

function Read-AllocationFile([string]$Path) {
    $utilityPath = Convert-ToUtilityPath $Path
    return (docker run --rm -v "${gameDataVolume}:/data" alpine:3.22 cat $utilityPath) -join "`n"
}

function Test-AllocationFile([string]$Path) {
    $utilityPath = Convert-ToUtilityPath $Path
    docker run --rm -v "${gameDataVolume}:/data" alpine:3.22 `
        test -f $utilityPath
    return $LASTEXITCODE -eq 0
}

function Test-ContainerRunning([string]$ContainerName) {
    $running = docker inspect --format "{{.State.Running}}" $ContainerName 2>$null
    return $LASTEXITCODE -eq 0 -and ($running -join "").Trim() -eq "true"
}

function Wait-ContainerStopped(
    [string]$ContainerName,
    [int]$TimeoutSeconds = 45
) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (Test-ContainerRunning $ContainerName) {
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "Container $ContainerName did not stop within $TimeoutSeconds seconds."
        }
        Start-Sleep -Seconds 1
    }
}

function Wait-PaperReady(
    [string]$ContainerName,
    [DateTime]$StartedAfter,
    [int]$TimeoutSeconds = 90
) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $since = $StartedAfter.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    while ([DateTime]::UtcNow -lt $deadline) {
        $recentLogs = docker logs --since $since $ContainerName 2>&1
        if ($LASTEXITCODE -eq 0 -and ($recentLogs -join "`n") -match 'Done \([0-9.]+s\)!') {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Paper did not report ready within $TimeoutSeconds seconds."
}

function Copy-To-Allocation([string]$Source, [string]$Destination) {
    $sourceDirectory = Split-Path -Parent $Source
    $sourceName = Split-Path -Leaf $Source
    $utilityDestination = Convert-ToUtilityPath $Destination
    docker run --rm `
        -v "${gameDataVolume}:/data" `
        -v "${sourceDirectory}:/input:ro" `
        alpine:3.22 cp -- "/input/$sourceName" $utilityDestination
    Assert-LastExitCode "Copying $Source into the fixture volume"
}

function Set-LobbyConfiguration(
    [string]$LobbyMode,
    [string]$LobbyAddress
) {
    $velocityPath = "$velocityVolume/velocity.toml"
    $slsPath = "$pluginData/config.yml"
    $velocity = Read-AllocationFile $velocityPath
    $sls = Read-AllocationFile $slsPath

    if ($LobbyMode -in @("velocity", "external")) {
        $servers = @"
[servers]
lobby = "$LobbyAddress"
try = ["lobby"]

"@
    } else {
        $servers = @"
[servers]
try = []

"@
    }

    $velocity = [regex]::Replace(
        $velocity,
        '(?ms)^\[servers\]\r?\n.*?(?=^\[forced-hosts\])',
        $servers
    )
    $lobbyModePattern = [regex]::new(
        '(?ms)(^lobby:\r?\n.*?^  mode:\s*)(velocity|external|managed)'
    )
    if (-not $lobbyModePattern.IsMatch($sls)) {
        throw "Could not locate lobby.mode in the current SLS-LITE config."
    }
    $sls = $lobbyModePattern.Replace(
        $sls,
        "`${1}$LobbyMode",
        1
    )

    $temporaryVelocity = Join-Path $env:TEMP "sls-lite-velocity.toml"
    $temporarySls = Join-Path $env:TEMP "sls-lite-config.yml"
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($temporaryVelocity, $velocity, $utf8)
    [System.IO.File]::WriteAllText($temporarySls, $sls, $utf8)
    try {
        Copy-To-Allocation $temporaryVelocity $velocityPath
        Copy-To-Allocation $temporarySls $slsPath
        Invoke-VolumeTool @(
            "chown",
            "999:989",
            (Convert-ToUtilityPath $velocityPath),
            (Convert-ToUtilityPath $slsPath)
        )
    } finally {
        Remove-Item -LiteralPath $temporaryVelocity, $temporarySls `
            -Force -ErrorAction SilentlyContinue
    }
}

function Set-PaperVelocityForwarding([string]$LobbyVolume) {
    $paperPath = "$LobbyVolume/config/paper-global.yml"
    $secret = (Read-AllocationFile "$velocityVolume/forwarding.secret").Trim()
    if ([string]::IsNullOrWhiteSpace($secret) -or $secret.Length -gt 1024) {
        throw "Velocity's forwarding secret is missing or unexpectedly large."
    }

    $paper = Read-AllocationFile $paperPath
    $velocityStart = $paper.IndexOf("  velocity:")
    if ($velocityStart -lt 0) {
        throw "Could not locate proxies.velocity in Paper's generated configuration."
    }
    $boundaryPattern = [regex]::new('(?m)^(?:  \S|\S)')
    $sectionBoundary = $boundaryPattern.Match(
        $paper,
        $velocityStart + "  velocity:".Length
    )
    if (-not $sectionBoundary.Success) {
        throw "Could not determine the end of Paper's proxies.velocity section."
    }
    $velocityEnd = $sectionBoundary.Index

    $velocity = $paper.Substring($velocityStart, $velocityEnd - $velocityStart)
    $enabledPattern = [regex]::new('(?m)(^    enabled:\s*)\S+\s*$')
    $secretPattern = [regex]::new('(?m)(^    secret:\s*).*$')
    if (-not $enabledPattern.IsMatch($velocity) -or -not $secretPattern.IsMatch($velocity)) {
        throw "Paper's generated proxies.velocity section has an unsupported shape."
    }
    $velocity = $enabledPattern.Replace($velocity, '${1}true', 1)
    $quotedSecret = $secret.Replace("'", "''")
    $velocity = $secretPattern.Replace($velocity, "`${1}'$quotedSecret'", 1)
    $paper = $paper.Substring(0, $velocityStart) + $velocity + `
        $paper.Substring($velocityEnd)

    $temporaryPaper = Join-Path $env:TEMP "sls-lite-paper-global.yml"
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($temporaryPaper, $paper, $utf8)
    try {
        Copy-To-Allocation $temporaryPaper $paperPath
        Invoke-VolumeTool @(
            "chown",
            "999:989",
            (Convert-ToUtilityPath $paperPath)
        )
    } finally {
        Remove-Item -LiteralPath $temporaryPaper -Force -ErrorAction SilentlyContinue
    }
}

$runningContainers = docker ps --format "{{.Names}}"
Assert-LastExitCode "Listing Docker containers"
if ($runningContainers -notcontains $panelContainer) {
    throw "The containerized Pterodactyl Panel is not running."
}

if ($Mode -in @("velocity", "external")) {
    $lobby = Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @()
    $lobbyVolume = "$allocationRoot/$($lobby.uuid)"
    if (Test-ContainerRunning $lobby.uuid) {
        Invoke-PanelHelper `
            $helper `
            "create-pterodactyl-paper-lobby-local.php" `
            @("--stop") | Out-Null
        Wait-ContainerStopped $lobby.uuid
    }
    $paperCacheRoot = Convert-ToUtilityPath "$pluginData/software/paper"
    $paperCandidates = @(
        docker run --rm -v "${gameDataVolume}:/data" alpine:3.22 `
            find $paperCacheRoot -mindepth 2 -maxdepth 2 -type f -name paper.jar
    )
    Assert-LastExitCode "Discovering a cached Paper fixture artifact"
    $paperUtilityPath = $paperCandidates | Sort-Object | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($paperUtilityPath) -or -not $paperUtilityPath.StartsWith("/data/")) {
        throw "No cached Paper artifact is available for the external lobby fixture."
    }
    $paperSource = "$volumeDataRoot/$($paperUtilityPath.Substring('/data/'.Length))"
    Invoke-VolumeTool @("mkdir", "-p", (Convert-ToUtilityPath $lobbyVolume))
    Invoke-VolumeTool @(
        "cp",
        "--",
        (Convert-ToUtilityPath $paperSource),
        (Convert-ToUtilityPath "$lobbyVolume/server.jar")
    )
    Copy-To-Allocation `
        (Join-Path $PSScriptRoot "fixtures\external-lobby-eula.txt") `
        "$lobbyVolume/eula.txt"
    Copy-To-Allocation `
        (Join-Path $PSScriptRoot "fixtures\external-lobby-server.properties") `
        "$lobbyVolume/server.properties"
    Invoke-VolumeTool @("chown", "-R", "999:989", (Convert-ToUtilityPath $lobbyVolume))

    $lobby = Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @("--mark-installed")
    docker restart $wingsContainer | Out-Null
    Assert-LastExitCode "Reloading Wings"
    Start-Sleep -Seconds 4
    Set-LobbyConfiguration $Mode $lobby.container_address
    $paperConfiguration = "$lobbyVolume/config/paper-global.yml"
    if (-not (Test-AllocationFile $paperConfiguration)) {
        $bootstrapStarted = [DateTime]::UtcNow
        Invoke-PanelHelper `
            $helper `
            "create-pterodactyl-paper-lobby-local.php" `
            @("--start") | Out-Null
        Wait-PaperReady $lobby.uuid $bootstrapStarted
        Invoke-PanelHelper `
            $helper `
            "create-pterodactyl-paper-lobby-local.php" `
            @("--stop") | Out-Null
        Wait-ContainerStopped $lobby.uuid
    }
    Set-PaperVelocityForwarding $lobbyVolume
    $paperStarted = [DateTime]::UtcNow
    Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @("--start") | Out-Null
    Wait-PaperReady $lobby.uuid $paperStarted
} else {
    Set-LobbyConfiguration "managed" ""
    $lobby = Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @("--existing-only")
    if ($lobby.exists) {
        Invoke-PanelHelper `
            $helper `
            "create-pterodactyl-paper-lobby-local.php" `
            @("--stop") | Out-Null
    }
}

Invoke-PanelHelper `
    $velocityHelper `
    "create-pterodactyl-velocity-local.php" `
    @("--restart") | Out-Null

Write-Host "SLS-LITE lobby mode is now $Mode."
Write-Host "Velocity Panel: http://localhost:8088/server/$velocityIdentifier"
if ($Mode -in @("velocity", "external")) {
    Write-Host "External lobby Panel identifier: $($lobby.identifier)"
    Write-Host "External lobby address inside Docker: $($lobby.container_address)"
}
