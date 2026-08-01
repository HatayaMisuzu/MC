param([Parameter(Mandatory = $true)][string]$ReleaseDir)

$ErrorActionPreference = 'Stop'
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$unicodeName = ([string][char]0x6D4B) + ([char]0x8BD5)
$work = Join-Path $tempRoot ("MCAC $unicodeName Space " + [Guid]::NewGuid().ToString('N'))
$copy = Join-Path $work 'Release Directory'
$primary = $null
$listener = $null

function Stop-Tree([Diagnostics.Process]$Process) {
    if (-not $Process -or $Process.HasExited) { return }
    & taskkill.exe /PID $Process.Id /T /F 2>$null | Out-Null
    [void]$Process.WaitForExit(5000)
}

function New-StartInfo([string]$Executable, [string]$Arguments, [string]$LocalAppData) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.Arguments = $Arguments
    $start.WorkingDirectory = $env:TEMP
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $start.Environment['LOCALAPPDATA'] = $LocalAppData
    $start.Environment['MCAC_WEB_ROOT'] = Join-Path $copy 'web'
    return $start
}

try {
    New-Item -ItemType Directory -Path $copy | Out-Null
    Copy-Item -Path (Join-Path $release '*') -Destination $copy -Recurse -Force
    $exe = Join-Path $copy 'mcac.exe'
    $local = Join-Path $work 'Local State'
    $state = Join-Path $work 'Launch State.json'
    New-Item -ItemType Directory -Path $local | Out-Null

    $start = New-StartInfo $exe '' $local
    $start.Environment['MCAC_NO_BROWSER'] = 'true'
    $start.Environment['MCAC_WEB_STATE_FILE'] = $state
    $primary = [Diagnostics.Process]::new()
    $primary.StartInfo = $start
    if (-not $primary.Start()) { throw 'Packaged no-argument mcac.exe did not start' }
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    while (-not (Test-Path -LiteralPath $state) -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $state)) {
        throw "Packaged no-argument launch failed in a Unicode/space path: $($primary.StandardError.ReadToEnd())"
    }
    $server = Get-Content -LiteralPath $state -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($server.bind -ne '127.0.0.1' -or $server.port -lt 1) {
        throw 'Packaged no-argument launch published invalid state'
    }
    $bootstrap = Join-Path $local 'MinecraftAICompanion\bootstrap.log'
    if (-not (Test-Path -LiteralPath $bootstrap) -or
            (Get-Content -LiteralPath $bootstrap -Raw -Encoding UTF8) -notmatch 'mode=web-default') {
        throw 'Packaged launch did not publish a bounded bootstrap journal'
    }
    Write-Output 'No-argument packaged launch passed from a Unicode/space path.'
    Stop-Tree $primary

    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $occupiedPort = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $conflictLocal = Join-Path $work 'Conflict State'
    New-Item -ItemType Directory -Path $conflictLocal | Out-Null
    $conflictStart = New-StartInfo $exe "web --no-browser --port $occupiedPort" $conflictLocal
    $conflict = [Diagnostics.Process]::new()
    $conflict.StartInfo = $conflictStart
    try {
        if (-not $conflict.Start()) { throw 'Port-conflict probe did not start' }
        if (-not $conflict.WaitForExit(20000)) {
            Stop-Tree $conflict
            throw 'Port-conflict probe did not fail within the process boundary'
        }
        $conflictError = $conflict.StandardError.ReadToEnd()
        $conflictLog = Join-Path $conflictLocal 'MinecraftAICompanion\bootstrap.log'
        if ($conflict.ExitCode -eq 0 -or $conflictError -notmatch 'Unable to start HTML terminal' -or
                -not (Test-Path -LiteralPath $conflictLog) -or
                (Get-Content -LiteralPath $conflictLog -Raw -Encoding UTF8) -notmatch 'ERROR html-terminal') {
            throw 'Port-conflict failure was not visible in both the console and bootstrap journal'
        }
    }
    finally { $conflict.Dispose() }
    $listener.Stop()
    $listener = $null
    Write-Output 'Port-conflict diagnostics passed.'

    $jvm = Join-Path $copy 'runtime\bin\server\jvm.dll'
    $disabledJvm = "$jvm.disabled"
    if (-not (Test-Path -LiteralPath $jvm)) { throw 'Packaged runtime jvm.dll is missing before fault injection' }
    Move-Item -LiteralPath $jvm -Destination $disabledJvm
    try {
        $preJvmStart = New-StartInfo $exe '--version' (Join-Path $work 'Pre JVM State')
        $preJvm = [Diagnostics.Process]::new()
        $preJvm.StartInfo = $preJvmStart
        try {
            if (-not $preJvm.Start()) { throw 'Pre-JVM failure probe could not start the launcher' }
            if (-not $preJvm.WaitForExit(15000)) {
                Stop-Tree $preJvm
                throw 'Pre-JVM failure probe did not exit'
            }
            $visible = $preJvm.StandardOutput.ReadToEnd() + $preJvm.StandardError.ReadToEnd()
            if ($preJvm.ExitCode -eq 0 -or [string]::IsNullOrWhiteSpace($visible)) {
                throw 'Packaged launcher did not expose the missing-JVM failure on its console boundary'
            }
        }
        finally { $preJvm.Dispose() }
    }
    finally { Move-Item -LiteralPath $disabledJvm -Destination $jvm }
    Write-Output 'Pre-JVM launcher failure visibility passed.'
}
finally {
    if ($listener) { $listener.Stop() }
    if ($primary) { Stop-Tree $primary; $primary.Dispose() }
    $resolvedWork = [IO.Path]::GetFullPath($work)
    if ($resolvedWork.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
            $resolvedWork.Length -gt $tempRoot.Length + 10) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Output 'Windows final artifact test passed.'
