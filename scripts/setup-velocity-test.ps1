param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
}

$java = Join-Path $JavaHome "bin\java.exe"
$repository = Split-Path -Parent $PSScriptRoot
$testServer = Join-Path $repository "test-server"
$pluginData = Join-Path $testServer "plugins\sls-lite"
$userAgent = "SLS-LITE-Test-Setup/0.1.0 (https://github.com/Yeetoxic/SLS-LITE)"
$velocityUrl = "https://fill-data.papermc.io/v1/objects/4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/velocity-4.0.0-6.jar"
$paperUrl = "https://fill-data.papermc.io/v1/objects/1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7/paper-26.1.2-74.jar"

if (-not (Test-Path -LiteralPath $java)) {
    throw "Java 25 was not found at $java"
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"

Push-Location $repository
try {
    & mvn clean package
    if ($LASTEXITCODE -ne 0) {
        throw "The SLS-LITE Maven build failed."
    }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path (Join-Path $testServer "plugins") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $pluginData "blueprints") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $pluginData "software-profiles") | Out-Null
$paperDirectory = Join-Path $pluginData "software\paper\26.1.2"
New-Item -ItemType Directory -Force -Path $paperDirectory | Out-Null

$headers = @{"User-Agent" = $userAgent}
if (-not (Test-Path -LiteralPath (Join-Path $testServer "velocity.jar"))) {
    Invoke-WebRequest -Uri $velocityUrl -Headers $headers `
        -OutFile (Join-Path $testServer "velocity.jar")
}
if (-not (Test-Path -LiteralPath (Join-Path $paperDirectory "paper.jar"))) {
    Invoke-WebRequest -Uri $paperUrl -Headers $headers `
        -OutFile (Join-Path $paperDirectory "paper.jar")
}
Copy-Item -LiteralPath (Join-Path $repository "target\sls-lite-0.1.0-SNAPSHOT.jar") `
    -Destination (Join-Path $testServer "plugins\sls-lite.jar") -Force

Set-Content -LiteralPath (Join-Path $pluginData "config.yml") -Encoding utf8 -Value @'
resources:
  total_memory_mib: 1024
network:
  ports:
    start: 25600
    end: 25610
paths:
  instances: instances
'@

Set-Content -LiteralPath (Join-Path $pluginData "blueprints\smoke.yml") -Encoding utf8 -Value @'
blueprint:
  id: smoke
  name: Local Smoke Test
  type: test
server:
  software: paper
  version: "26.1.2"
  limits:
    memory_limit: 768
save: false
annotations:
  sls-lite:
    test-only: true
'@

Set-Content -LiteralPath (Join-Path $pluginData "software-profiles\paper.yml") `
    -Encoding utf8 -Value @'
software:
  id: paper
  base_directory: software/paper/{version}
  server_jar: paper.jar
launch:
  java: java
  jvm_arguments:
    - "-Xms{memory_mib}M"
    - "-Xmx{memory_mib}M"
  server_arguments:
    - "--nogui"
readiness:
  pattern: 'Done \([^)]+\)! For help'
  timeout_seconds: 180
shutdown:
  command: stop
  timeout_seconds: 30
'@
Set-Content -LiteralPath (Join-Path $paperDirectory "eula.txt") `
    -Encoding ascii -Value "eula=true"

$velocityConfig = Join-Path $testServer "velocity.toml"
if (-not (Test-Path -LiteralPath $velocityConfig)) {
    Push-Location $testServer
    try {
        "end" | & $java -Xms256M -Xmx512M -jar velocity.jar
        if ($LASTEXITCODE -ne 0) {
            throw "Velocity's initial configuration boot failed."
        }
    } finally {
        Pop-Location
    }
}

$config = Get-Content -LiteralPath $velocityConfig -Raw
$config = $config.Replace('bind = "0.0.0.0:25565"', 'bind = "127.0.0.1:25565"')
$config = $config.Replace("online-mode = true", "online-mode = false")
$config = [regex]::Replace(
    $config,
    '(?s)\[servers\].*?(?=\[forced-hosts\])',
    "[servers]`r`ntry = []`r`n`r`n"
)
$config = [regex]::Replace(
    $config,
    '(?s)\[forced-hosts\].*?(?=\[advanced\])',
    "[forced-hosts]`r`n`r`n"
)
Set-Content -LiteralPath $velocityConfig -Encoding utf8 -Value $config

Set-Content -LiteralPath (Join-Path $testServer "start.ps1") -Encoding utf8 -Value @"
`$ErrorActionPreference = "Stop"
& "$java" -Xms256M -Xmx512M -jar velocity.jar
"@

Write-Host "Velocity test server is ready at $testServer"
Write-Host "Run: cd test-server; .\start.ps1"
