[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeHome = Join-Path $root 'build\e2e-runtime'
$fabric = Join-Path $root 'minecraft\fabric-1.21.1'
$runtimeBat = Join-Path $root 'runtime\runtime-app\build\install\runtime-app\bin\runtime-app.bat'
$config = Join-Path $runtimeHome 'runtime.yml'
$token = Join-Path $runtimeHome 'data\pairing.token'
$gameToken = Join-Path $fabric 'build\gametest\config\minecraft-ai-companion\runtime.token'
$gameRun = Join-Path $fabric 'build\gametest'

if (-not (Test-Path -LiteralPath $runtimeBat)) {
    throw 'Runtime distribution is missing; run :runtime:runtime-app:installDist first.'
}
if (Test-Path -LiteralPath $runtimeHome) {
    Remove-Item -LiteralPath $runtimeHome -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runtimeHome | Out-Null

function Start-TestProcess([string]$file, [string]$arguments, [string]$workingDirectory, [bool]$captureOutput) {
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

function Read-ProcessLog([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return '' }
    return [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
}

function Invoke-RuntimeCommand(
    [string]$pairingToken,
    [string]$companionId,
    [string]$type,
    [hashtable]$arguments,
    [string]$originalText
) {
    $body = @{
        commandId = "e2e-$([Guid]::NewGuid())"
        companionId = $companionId
        type = $type
        arguments = $arguments
        originalText = $originalText
    } | ConvertTo-Json -Compress -Depth 10
    return Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18766/commands' -Headers @{
        Authorization = "Bearer $pairingToken"
    } -ContentType 'application/json' -Body $body -TimeoutSec 5
}

function Wait-RuntimeTaskState([string]$pairingToken, [string]$taskId, [string]$expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $snapshot = Invoke-RestMethod -Uri "http://127.0.0.1:18766/tasks/$taskId" -Headers @{
            Authorization = "Bearer $pairingToken"
        } -TimeoutSec 3
        if ($snapshot.task.state -eq $expected) { return $snapshot }
        if ($snapshot.task.state -in @('COMPLETED', 'FAILED', 'CANCELLED') -and $snapshot.task.state -ne $expected) {
            throw "Runtime task entered $($snapshot.task.state) while waiting for $expected."
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Runtime task did not reach $expected in time (last=$($snapshot.task.state))."
}

$runtime = $null
$game = $null
$commandEvidence = @()
try {
    Write-Output '[runtime-e2e] starting Runtime'
    $runtimeLib = Join-Path (Split-Path -Parent (Split-Path -Parent $runtimeBat)) 'lib'
    $runtimeClasspath = ((Get-ChildItem -LiteralPath $runtimeLib -Filter '*.jar' -File).FullName -join ';')
    if ([string]::IsNullOrWhiteSpace($runtimeClasspath)) { throw 'Runtime classpath is empty.' }
    $javaHome = if ($env:MCAC_TEST_JAVA_HOME) { $env:MCAC_TEST_JAVA_HOME } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { $null }
    if (-not $javaHome) { throw 'A real Java home was not provided by Gradle.' }
    $java = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'The Gradle Java executable is missing.' }
    $runtimeArgs = "-classpath `"$runtimeClasspath`" com.mccompanion.runtime.RuntimeMain --config `"$config`""
    $runtime = Start-TestProcess $java $runtimeArgs $root $true
    $runtimeOut = $runtime.StandardOutput.ReadToEndAsync()
    $runtimeErr = $runtime.StandardError.ReadToEndAsync()

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (-not (Test-Path -LiteralPath $token)) {
        if ($runtime.HasExited) { throw "Runtime exited before creating its pairing token ($($runtime.ExitCode))." }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Runtime pairing token was not created in time.' }
        Start-Sleep -Milliseconds 200
    }
    Write-Output '[runtime-e2e] Runtime token ready; starting Fabric GameTest'

    if (Test-Path -LiteralPath $gameRun) {
        Remove-Item -LiteralPath $gameRun -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $gameToken) | Out-Null
    Copy-Item -LiteralPath $token -Destination $gameToken -Force
    $pairingToken = (Get-Content -Raw -LiteralPath $token).Trim()

    $gameOutFile = Join-Path $runtimeHome 'fabric-gametest.out.log'
    $gameErrFile = Join-Path $runtimeHome 'fabric-gametest.err.log'
    $gameArgs = "/d /s /c `"`"$fabric\gradlew.bat`" runGameTest -PmccompanionRuntimeE2E=true --no-daemon > `"$gameOutFile`" 2> `"$gameErrFile`"`""
    $game = Start-TestProcess 'cmd.exe' $gameArgs $fabric $false

    $gameLog = Join-Path $gameRun 'logs\latest.log'
    $registrationDeadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        if ($game.HasExited) { throw "Fabric Runtime GameTest exited before companion registration ($($game.ExitCode))." }
        if ([DateTime]::UtcNow -gt $registrationDeadline) { throw 'Fabric companion did not register with Runtime in time.' }
        Start-Sleep -Milliseconds 200
        $health = $null
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18766/health' -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 2
        } catch {
            # The authenticated management endpoint may still be starting.
        }
        $registrationReady = (Test-Path -LiteralPath $gameLog) -and
            ((Get-Content -Raw -LiteralPath $gameLog) -match 'Runtime bridge connected') -and
            ((Get-Content -Raw -LiteralPath $gameLog) -match 'companion_created') -and
            ((Get-Content -Raw -LiteralPath $gameLog) -match 'runtime_e2e_ready companion=([0-9a-f-]{36})') -and
            ($null -ne $health) -and ($health.onlineCompanionCount -ge 1)
    } until ($registrationReady)
    Write-Output '[runtime-e2e] Fabric companion registered; exercising behavior controls'

    $readyMatch = [regex]::Match((Get-Content -Raw -LiteralPath $gameLog),
        'runtime_e2e_ready companion=([0-9a-f-]{36})')
    if (-not $readyMatch.Success) { throw 'Runtime E2E readiness marker has no companion id.' }
    $companionId = $readyMatch.Groups[1].Value

    $follow = Invoke-RuntimeCommand $pairingToken $companionId 'FOLLOW' @{} 'follow'
    if (-not $follow.accepted -or -not $follow.taskId) { throw "FOLLOW was rejected: $($follow.code)" }
    $running = Wait-RuntimeTaskState $pairingToken $follow.taskId 'RUNNING'
    $commandEvidence += [pscustomobject]@{ command = 'FOLLOW'; reply = $follow; snapshot = $running }

    $pause = Invoke-RuntimeCommand $pairingToken $companionId 'STOP' @{ action = 'pause' } 'pause'
    if (-not $pause.accepted) { throw "PAUSE was rejected: $($pause.code)" }
    $paused = Wait-RuntimeTaskState $pairingToken $follow.taskId 'PAUSED'
    $commandEvidence += [pscustomobject]@{ command = 'PAUSE'; reply = $pause; snapshot = $paused }

    $resume = Invoke-RuntimeCommand $pairingToken $companionId 'STOP' @{ action = 'resume' } 'resume'
    if (-not $resume.accepted) { throw "RESUME was rejected: $($resume.code)" }
    $resumed = Wait-RuntimeTaskState $pairingToken $follow.taskId 'RUNNING'
    $commandEvidence += [pscustomobject]@{ command = 'RESUME'; reply = $resume; snapshot = $resumed }

    $stop = Invoke-RuntimeCommand $pairingToken $companionId 'STOP' @{ action = 'cancel' } 'stop'
    if (-not $stop.accepted) { throw "STOP was rejected: $($stop.code)" }
    $cancelled = Wait-RuntimeTaskState $pairingToken $follow.taskId 'CANCELLED'
    $commandEvidence += [pscustomobject]@{ command = 'STOP'; reply = $stop; snapshot = $cancelled }

    Write-Output '[runtime-e2e] behavior controls passed; waiting for Fabric GameTest shutdown'
    if (-not $game.WaitForExit(90000)) { throw 'Fabric Runtime GameTest did not exit in time.' }
    Write-Output '[runtime-e2e] Fabric GameTest exited; stopping Runtime'
    $runtime.StandardInput.WriteLine('quit')
    $runtime.StandardInput.Flush()
    if (-not $runtime.WaitForExit(15000)) {
        $runtime.Kill($true)
        if (-not $runtime.WaitForExit(5000)) { throw 'Runtime process tree did not stop after forced shutdown.' }
        throw 'Runtime did not shut down after quit.'
    }
    Write-Output '[runtime-e2e] Runtime exited; collecting evidence'

    $gameStdout = Read-ProcessLog $gameOutFile
    $gameStderr = Read-ProcessLog $gameErrFile
    $runtimeStdout = $runtimeOut.GetAwaiter().GetResult()
    $runtimeStderr = $runtimeErr.GetAwaiter().GetResult()
    New-Item -ItemType Directory -Force -Path (Join-Path $runtimeHome 'evidence') | Out-Null
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\fabric-gametest.out.log'), $gameStdout, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\fabric-gametest.err.log'), $gameStderr, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\runtime-cli.out.log'), $runtimeStdout, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\runtime-cli.err.log'), $runtimeStderr, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\runtime-commands.json'),
        ($commandEvidence | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))

    if ($game.ExitCode -ne 0) { throw "Fabric GameTest failed with exit code $($game.ExitCode)." }
    if ($runtime.ExitCode -ne 0) { throw "Runtime failed with exit code $($runtime.ExitCode)." }
    if ($gameStdout -notmatch 'Runtime bridge connected') { throw 'Fabric log has no successful Runtime handshake.' }
    if ($gameStdout -notmatch 'All [0-9]+ required tests passed') { throw 'Fabric lifecycle GameTest did not pass.' }
    if ($commandEvidence.Count -ne 4 -or $commandEvidence[-1].snapshot.task.state -ne 'CANCELLED') {
        throw 'Runtime command evidence did not complete the safe cancellation path.'
    }
    if ($runtimeStderr -match 'Invalid CompanionStatus payload' -or $runtimeStdout -match 'Invalid CompanionStatus payload') {
        throw 'Runtime rejected a CompanionStatus payload during E2E.'
    }
    if ($runtimeStdout -match '(?m)^CLI_ERROR:') {
        throw 'Runtime CLI emitted a command error after companion registration.'
    }
    if ($runtimeStderr -match '(?m)^.*SEVERE.*$') {
        throw 'Runtime emitted a SEVERE error during E2E.'
    }
    Write-Output 'Runtime/Fabric E2E passed: handshake, companion registration, lease, follow, pause, resume, stop, safe shutdown.'
} finally {
    if ($game -and -not $game.HasExited) { $game.Kill($true) }
    if ($runtime -and -not $runtime.HasExited) { $runtime.Kill($true) }
    Remove-Item -LiteralPath $gameToken -Force -ErrorAction SilentlyContinue
}
