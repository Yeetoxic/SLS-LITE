param(
    [ValidateSet("windows", "native")]
    [string]$Mode = "native",
    [string]$VelocityUuid = "c165ae9c-1f88-4460-967e-1c0193d074d4"
)

$ErrorActionPreference = "Stop"

$repository = Split-Path -Parent $PSScriptRoot
$pterodactyl = Join-Path $repository "infra\pterodactyl"
$baseCompose = Join-Path $pterodactyl "docker-compose.yml"
$nativeCompose = Join-Path $pterodactyl "docker-compose.native-storage.yml"
$environment = Join-Path $pterodactyl ".env.local"
$windowsWings = Join-Path $pterodactyl "state\wings\config.yml"
$nativeWingsDirectory = Join-Path $pterodactyl "state\wings-native"
$nativeWings = Join-Path $nativeWingsDirectory "config.yml"
$velocityHelper = Join-Path $PSScriptRoot "create-pterodactyl-velocity-local.php"
$panelContainer = "sls-ptero-panel"
$wingsContainer = "sls-ptero-wings"
$nativeVolume = "sls-ptero-native-game-data"
$nativeRoot = "/var/lib/docker/volumes/$nativeVolume/_data"
$windowsData = Join-Path $pterodactyl "state\game-data"

function Assert-LastExitCode([string]$Operation) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE."
    }
}

function Invoke-PanelPower([string]$Action) {
    $containerPath = "/tmp/create-pterodactyl-velocity-local.php"
    docker cp $velocityHelper "${panelContainer}:$containerPath" | Out-Null
    Assert-LastExitCode "Copying the Velocity Panel helper"
    docker exec -e PANEL_ROOT=/app $panelContainer `
        php $containerPath "--$Action" | Out-Null
    Assert-LastExitCode "Sending Velocity power action '$Action'"
}

function Wait-VelocityStopped {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $running = docker ps `
            --filter "name=$VelocityUuid" `
            --format "{{.Names}}"
        Assert-LastExitCode "Inspecting running Velocity containers"
        if ($running -notcontains $VelocityUuid) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Velocity did not stop within 60 seconds."
}

function Wait-VelocityStarted {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $running = docker ps `
            --filter "name=$VelocityUuid" `
            --format "{{.Names}}"
        Assert-LastExitCode "Inspecting running Velocity containers"
        if ($running -contains $VelocityUuid) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Velocity did not start within 90 seconds."
}

function Remove-StoppedVelocityContainer {
    $containers = docker container ls -a --format "{{.Names}}"
    Assert-LastExitCode "Listing Docker containers"
    if ($containers -contains $VelocityUuid) {
        docker rm $VelocityUuid | Out-Null
        Assert-LastExitCode "Removing the stopped Velocity container"
    }
}

function Invoke-Compose([string[]]$Files, [string[]]$Arguments) {
    $command = @(
        "compose",
        "--project-directory", $pterodactyl,
        "--env-file", $environment
    )
    foreach ($file in $Files) {
        $command += @("-f", $file)
    }
    $command += $Arguments
    & docker @command
    Assert-LastExitCode "Running Docker Compose"
}

function Write-NativeWingsConfiguration {
    if (-not (Test-Path -LiteralPath $windowsWings)) {
        throw "Wings configuration is missing: $windowsWings"
    }
    New-Item -ItemType Directory -Force -Path $nativeWingsDirectory | Out-Null
    $configuration = Get-Content -Raw -LiteralPath $windowsWings
    $configuration = [regex]::Replace(
        $configuration,
        '(?m)^  root_directory: .+$',
        "  root_directory: $nativeRoot"
    )
    $configuration = [regex]::Replace(
        $configuration,
        '(?m)^  data: .+$',
        "  data: $nativeRoot/volumes"
    )
    $configuration = [regex]::Replace(
        $configuration,
        '(?m)^  archive_directory: .+$',
        "  archive_directory: $nativeRoot/archives"
    )
    $configuration = [regex]::Replace(
        $configuration,
        '(?m)^  backup_directory: .+$',
        "  backup_directory: $nativeRoot/backups"
    )
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($nativeWings, $configuration, $utf8)
}

function Initialize-NativeVolume {
    $volumes = docker volume ls `
        --filter "name=^$nativeVolume$" `
        --format "{{.Name}}"
    Assert-LastExitCode "Listing Docker volumes"
    $exists = $volumes -contains $nativeVolume
    if (-not $exists) {
        docker volume create $nativeVolume | Out-Null
        Assert-LastExitCode "Creating the native game-data volume"
    }
    $mountpoint = docker volume inspect `
        --format "{{.Mountpoint}}" `
        $nativeVolume
    Assert-LastExitCode "Resolving the native game-data volume"
    if ($mountpoint -ne $nativeRoot) {
        throw (
            "Native volume mountpoint '$mountpoint' does not match the " +
            "configured Wings path '$nativeRoot'."
        )
    }

    $marker = docker run --rm `
        --mount "type=volume,src=$nativeVolume,dst=/data" `
        alpine:3.22 `
        sh -c "if test -f /data/.sls-native-storage; then echo initialized; fi"
    Assert-LastExitCode "Inspecting the native game-data volume"
    if ($marker -eq "initialized") {
        return
    }

    $resolvedWindowsData = (Resolve-Path -LiteralPath $windowsData).Path
    docker run --rm `
        --mount "type=bind,src=$resolvedWindowsData,dst=/source,readonly" `
        --mount "type=volume,src=$nativeVolume,dst=/target" `
        alpine:3.22 `
        sh -c "cp -a /source/. /target/ && touch /target/.sls-native-storage"
    Assert-LastExitCode "Copying the Windows game-data snapshot into native storage"
}

$running = docker ps --format "{{.Names}}"
Assert-LastExitCode "Listing Docker containers"
if ($running -notcontains $panelContainer) {
    throw "The local Pterodactyl Panel is not running."
}

Invoke-PanelPower "stop"
Wait-VelocityStopped
docker stop $wingsContainer | Out-Null
Assert-LastExitCode "Stopping Wings"
Remove-StoppedVelocityContainer

if ($Mode -eq "native") {
    Write-NativeWingsConfiguration
    Initialize-NativeVolume
    Invoke-Compose `
        @($baseCompose, $nativeCompose) `
        @("up", "-d", "--no-deps", "--force-recreate", "wings")
} else {
    Invoke-Compose `
        @($baseCompose) `
        @("up", "-d", "--no-deps", "--force-recreate", "wings")
}

Start-Sleep -Seconds 5
Invoke-PanelPower "start"
Wait-VelocityStarted

Write-Host "Pterodactyl game storage mode is now $Mode."
Write-Host "Windows snapshot: $windowsData"
Write-Host "Native volume: $nativeVolume"
