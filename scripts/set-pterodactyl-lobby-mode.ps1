param(
    [ValidateSet("external", "managed")]
    [string]$Mode = "external",
    [string]$VelocityUuid = "c165ae9c-1f88-4460-967e-1c0193d074d4"
)

$ErrorActionPreference = "Stop"

$panelContainer = "sls-ptero-panel"
$wingsContainer = "sls-ptero-wings"
$velocityIdentifier = $VelocityUuid.Substring(0, 8)
$velocityVolume = "/var/lib/pterodactyl/volumes/$VelocityUuid"
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

function Copy-To-Wsl([string]$Source, [string]$Destination) {
    $sourceWsl = (wsl -d Ubuntu -e wslpath -a $Source).Trim()
    Assert-LastExitCode "Resolving $Source for WSL"
    wsl -d Ubuntu --user root -e cp -- $sourceWsl $Destination
    Assert-LastExitCode "Copying $Source to $Destination"
}

function Set-LobbyConfiguration(
    [string]$LobbyMode,
    [string]$LobbyAddress
) {
    $velocityPath = "$velocityVolume/velocity.toml"
    $slsPath = "$pluginData/config.yml"
    $velocity = (wsl -d Ubuntu -e cat $velocityPath) -join "`n"
    Assert-LastExitCode "Reading velocity.toml"
    $sls = (wsl -d Ubuntu -e cat $slsPath) -join "`n"
    Assert-LastExitCode "Reading SLS-LITE config.yml"

    if ($LobbyMode -eq "external") {
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
        '(?ms)(^lobby:\r?\n.*?^  mode:\s*)(external|managed)'
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
        Copy-To-Wsl $temporaryVelocity $velocityPath
        Copy-To-Wsl $temporarySls $slsPath
        wsl -d Ubuntu --user root -e chown 999:989 $velocityPath $slsPath
        Assert-LastExitCode "Restoring Velocity file ownership"
    } finally {
        Remove-Item -LiteralPath $temporaryVelocity, $temporarySls `
            -Force -ErrorAction SilentlyContinue
    }
}

$runningContainers = docker ps --format "{{.Names}}"
Assert-LastExitCode "Listing Docker containers"
if ($runningContainers -notcontains $panelContainer) {
    throw "The containerized Pterodactyl Panel is not running."
}

if ($Mode -eq "external") {
    $lobby = Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @()
    $paperSource = "$pluginData/software/paper/26.1.2/paper.jar"
    wsl -d Ubuntu -e test -f $paperSource
    Assert-LastExitCode "Locating the prepared Paper test JAR"
    wsl -d Ubuntu --user root -e mkdir -p $lobby.volume
    Assert-LastExitCode "Creating the external lobby volume"
    wsl -d Ubuntu --user root -e cp -- $paperSource "$($lobby.volume)/server.jar"
    Assert-LastExitCode "Copying Paper into the external lobby volume"
    Copy-To-Wsl `
        (Join-Path $PSScriptRoot "fixtures\external-lobby-eula.txt") `
        "$($lobby.volume)/eula.txt"
    Copy-To-Wsl `
        (Join-Path $PSScriptRoot "fixtures\external-lobby-server.properties") `
        "$($lobby.volume)/server.properties"
    wsl -d Ubuntu --user root -e chown -R 999:989 $lobby.volume
    Assert-LastExitCode "Setting external lobby file ownership"

    $lobby = Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @("--mark-installed")
    docker restart $wingsContainer | Out-Null
    Assert-LastExitCode "Reloading Wings"
    Start-Sleep -Seconds 4
    Set-LobbyConfiguration "external" $lobby.container_address
    Invoke-PanelHelper `
        $helper `
        "create-pterodactyl-paper-lobby-local.php" `
        @("--start") | Out-Null
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
if ($Mode -eq "external") {
    Write-Host "External lobby Panel identifier: $($lobby.identifier)"
    Write-Host "External lobby address inside Docker: $($lobby.container_address)"
}
