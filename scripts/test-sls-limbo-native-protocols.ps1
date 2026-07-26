param(
    [int]$Port = 25575,
    [string[]]$Versions = @(
        "1.13.2",
        "1.16.5",
        "1.20.4",
        "1.21.4",
        "1.21.11"
    )
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $repository `
    "src\main\resources\limbo\nanolimbo-1.13.0.jar"
$temporaryRoot = [IO.Path]::GetFullPath($env:TEMP)
$runDirectory = Join-Path $temporaryRoot `
    ("sls-lite-protocol-" + [guid]::NewGuid().ToString("N"))
$standardOutput = Join-Path $runDirectory "nanolimbo.stdout.log"
$standardError = Join-Path $runDirectory "nanolimbo.stderr.log"
$process = $null

if (-not (Test-Path -LiteralPath $runtime)) {
    throw "The pinned SLS-Limbo runtime is missing: $runtime"
}

try {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($runtime)
    try {
        $entry = $archive.GetEntry("settings.yml")
        if ($null -eq $entry) {
            throw "The pinned runtime does not contain settings.yml."
        }
        $reader = [IO.StreamReader]::new($entry.Open())
        try {
            $settings = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }

    $settings = $settings.Replace('ip: "localhost"', 'ip: "127.0.0.1"')
    $settings = $settings.Replace("port: 65535", "port: $Port")
    $settings = $settings.Replace("transportType: EPOLL", "transportType: NIO")
    $settings = $settings.Replace("workerGroup: 4", "workerGroup: 2")
    [IO.File]::WriteAllText(
        (Join-Path $runDirectory "settings.yml"),
        $settings,
        [Text.UTF8Encoding]::new($false)
    )

    $process = Start-Process `
        -FilePath "java" `
        -ArgumentList @("-Xms64M", "-Xmx96M", "-jar", $runtime) `
        -WorkingDirectory $runDirectory `
        -RedirectStandardOutput $standardOutput `
        -RedirectStandardError $standardError `
        -WindowStyle Hidden `
        -PassThru

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        try {
            $client = [Net.Sockets.TcpClient]::new()
            $client.Connect("127.0.0.1", $Port)
            $client.Dispose()
            $ready = $true
            break
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) {
        $output = if (Test-Path $standardOutput) {
            Get-Content -LiteralPath $standardOutput -Raw
        } else {
            ""
        }
        $errors = if (Test-Path $standardError) {
            Get-Content -LiteralPath $standardError -Raw
        } else {
            ""
        }
        throw "NanoLimbo did not become ready.`n$output`n$errors"
    }

    & (Join-Path $PSScriptRoot "test-sls-limbo-protocols.ps1") `
        -HostName "127.0.0.1" `
        -Port $Port `
        -Versions $Versions `
        -ExpectedBrand "NanoLimbo"
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        $process.WaitForExit(5000) | Out-Null
    }
    $resolvedRunDirectory = [IO.Path]::GetFullPath($runDirectory)
    if ($resolvedRunDirectory.StartsWith(
            $temporaryRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        ) -and (Test-Path -LiteralPath $resolvedRunDirectory)) {
        Remove-Item -LiteralPath $resolvedRunDirectory -Recurse -Force
    }
}
