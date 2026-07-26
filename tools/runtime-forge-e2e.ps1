[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeHome = Join-Path $root 'build\e2e-runtime-forge'
$forge = Join-Path $root 'minecraft\forge-1.20.1'
$runtimeBat = Join-Path $root 'runtime\runtime-app\build\install\runtime-app\bin\runtime-app.bat'
$config = Join-Path $runtimeHome 'runtime.yml'
$token = Join-Path $runtimeHome 'data\pairing.token'
$gameRun = Join-Path $forge 'build\gametest'
$gameToken = Join-Path $gameRun 'config\minecraft-ai-companion\runtime.token'
$gameOut = Join-Path $runtimeHome 'forge-gametest.out.log'
$gameErr = Join-Path $runtimeHome 'forge-gametest.err.log'

function Start-TestProcess(
    [string]$file,
    [string]$arguments,
    [string]$workingDirectory,
    [bool]$captureOutput
) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $file
    $start.Arguments = $arguments
    $start.WorkingDirectory = $workingDirectory
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $captureOutput
    $start.RedirectStandardError = $captureOutput
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { throw "Unable to start $file" }
    return $process
}

function Stop-TestProcessTree([Diagnostics.Process]$process) {
    if (-not $process -or $process.HasExited) { return }
    $children = Get-CimInstance Win32_Process |
            Where-Object { $_.ParentProcessId -eq $process.Id }
    foreach ($child in $children) {
        try {
            $childProcess = [Diagnostics.Process]::GetProcessById($child.ProcessId)
            Stop-TestProcessTree $childProcess
        } catch {
            # A child that exits during cleanup is already safely stopped.
        }
    }
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    try { $process.WaitForExit() } catch { }
}

function Invoke-RuntimeCommand(
    [string]$pairingToken,
    [string]$companionId,
    [string]$type,
    [hashtable]$arguments,
    [string]$originalText
) {
    $body = @{
        commandId = "forge-e2e-$([Guid]::NewGuid())"
        companionId = $companionId
        type = $type
        arguments = $arguments
        originalText = $originalText
    } | ConvertTo-Json -Compress -Depth 10
    return Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18776/commands' -Headers @{
        Authorization = "Bearer $pairingToken"
    } -ContentType 'application/json' -Body $body -TimeoutSec 5
}

function Wait-RuntimeTaskState(
    [string]$pairingToken,
    [string]$taskId,
    [string]$expected
) {
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    do {
        try {
            $snapshot = Invoke-RestMethod -Uri "http://127.0.0.1:18776/tasks/$taskId" -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 3
            if ($snapshot.task.state -eq $expected) { return $snapshot }
        } catch {
            # The task may still be crossing its accepted/running boundary.
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Runtime task $taskId did not reach $expected."
}

if (-not (Test-Path -LiteralPath $runtimeBat)) {
    throw 'Runtime distribution is missing; run :runtime:runtime-app:installDist first.'
}
if (Test-Path -LiteralPath $runtimeHome) {
    Remove-Item -LiteralPath $runtimeHome -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runtimeHome | Out-Null
@"
server:
  bind: 127.0.0.1
  port: 8766
  management_port: 18776
  profile_id: forge-e2e
  instance_id: forge-e2e
  token_file: ./data/pairing.token
  heartbeat_seconds: 15
  allow_remote: false
database:
  path: ./data/companion.db
provider:
  mode: rules
brain:
  mode: disabled
logging:
  file: ./logs/runtime.log
  console: true
"@ | Set-Content -LiteralPath $config -Encoding UTF8

$runtime = $null
$game = $null
try {
    $runtimeLib = Join-Path (Split-Path -Parent (Split-Path -Parent $runtimeBat)) 'lib'
    $runtimeClasspath = ((Get-ChildItem -LiteralPath $runtimeLib -Filter '*.jar' -File).FullName -join ';')
    $javaHome = if ($env:MCAC_TEST_JAVA_HOME) { $env:MCAC_TEST_JAVA_HOME } else { $env:JAVA_HOME }
    if (-not $javaHome) { throw 'A real Java home was not provided.' }
    $java = Join-Path $javaHome 'bin\java.exe'
    $runtimeArgs = "-classpath `"$runtimeClasspath`" com.mccompanion.runtime.RuntimeMain --config `"$config`""
    $runtime = Start-TestProcess $java $runtimeArgs $root $true
    $runtimeOut = $runtime.StandardOutput.ReadToEndAsync()
    $runtimeErr = $runtime.StandardError.ReadToEndAsync()

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (-not (Test-Path -LiteralPath $token)) {
        if ($runtime.HasExited) { throw "Runtime exited early ($($runtime.ExitCode))." }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Runtime pairing token was not created in time.' }
        Start-Sleep -Milliseconds 200
    }
    Write-Output '[runtime-forge-e2e] Runtime token ready'

    if (Test-Path -LiteralPath $gameRun) {
        Remove-Item -LiteralPath $gameRun -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $gameToken) | Out-Null
    Copy-Item -LiteralPath $token -Destination $gameToken -Force
    $pairingToken = (Get-Content -Raw -LiteralPath $token).Trim()
    $gameArgs = "/d /s /c `"`"$forge\gradlew.bat`" runGameTestServer -PmccompanionRuntimeE2E=true --no-daemon > `"$gameOut`" 2> `"$gameErr`"`""
    $game = Start-TestProcess 'cmd.exe' $gameArgs $forge $false

    $gameLog = Join-Path $gameRun 'logs\latest.log'
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        if ($game.HasExited) { throw "Forge Runtime GameTest exited early ($($game.ExitCode))." }
        if ([DateTime]::UtcNow -gt $readyDeadline) { throw 'Forge companion did not register in time.' }
        Start-Sleep -Milliseconds 200
        $log = if (Test-Path -LiteralPath $gameLog) {
            [string](Get-Content -Raw -LiteralPath $gameLog)
        } else {
            ''
        }
        $health = $null
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18776/health' -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 2
        } catch {
            # The authenticated status publisher may still be registering the body.
        }
        $match = [regex]::Match($log, 'forge_runtime_e2e_ready companion=([0-9a-f-]{36})')
    } until ($match.Success -and $log -match 'Runtime bridge connected' -and $null -ne $health -and $health.onlineCompanionCount -ge 1)
    $companionId = $match.Groups[1].Value
    Write-Output "[runtime-forge-e2e] connected companion=$companionId"

    $follow = Invoke-RuntimeCommand $pairingToken $companionId 'FOLLOW' @{} 'follow'
    if (-not $follow.accepted -or -not $follow.taskId) { throw "FOLLOW rejected: $($follow.code)" }
    $null = Wait-RuntimeTaskState $pairingToken $follow.taskId 'RUNNING'
    Write-Output '[runtime-forge-e2e] follow running'
    foreach ($control in @(
        @{ action = 'pause'; state = 'PAUSED' },
        @{ action = 'resume'; state = 'RUNNING' },
        @{ action = 'cancel'; state = 'CANCELLED' }
    )) {
        $reply = Invoke-RuntimeCommand $pairingToken $companionId 'STOP' @{
            action = $control.action
        } $control.action
        if (-not $reply.accepted) { throw "$($control.action) rejected: $($reply.code)" }
        $null = Wait-RuntimeTaskState $pairingToken $follow.taskId $control.state
        Write-Output "[runtime-forge-e2e] $($control.action) -> $($control.state)"
    }

    if (-not $game.WaitForExit(30000)) { throw 'Forge Runtime GameTest did not finish.' }
    if ($game.ExitCode -ne 0) { throw "Forge Runtime GameTest failed ($($game.ExitCode))." }
    Write-Output '[runtime-forge-e2e] authenticated handshake and behavior controls passed'
} finally {
    Stop-TestProcessTree $game
    Stop-TestProcessTree $runtime
}
