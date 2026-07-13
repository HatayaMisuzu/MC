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

function Start-RedirectedProcess([string]$file, [string]$arguments, [string]$workingDirectory) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $file
    $start.Arguments = $arguments
    $start.WorkingDirectory = $workingDirectory
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { throw "Unable to start $file" }
    return $process
}

$runtime = $null
$game = $null
try {
    $runtimeArgs = "/d /s /c `"`"$runtimeBat`" --config `"$config`"`""
    $runtime = Start-RedirectedProcess 'cmd.exe' $runtimeArgs $root
    $runtimeOut = $runtime.StandardOutput.ReadToEndAsync()
    $runtimeErr = $runtime.StandardError.ReadToEndAsync()

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (-not (Test-Path -LiteralPath $token)) {
        if ($runtime.HasExited) { throw "Runtime exited before creating its pairing token ($($runtime.ExitCode))." }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Runtime pairing token was not created in time.' }
        Start-Sleep -Milliseconds 200
    }

    if (Test-Path -LiteralPath $gameRun) {
        Remove-Item -LiteralPath $gameRun -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $gameToken) | Out-Null
    Copy-Item -LiteralPath $token -Destination $gameToken -Force

    $gameArgs = "/d /s /c `"`"$fabric\gradlew.bat`" runGameTest -PmccompanionRuntimeE2E=true --no-daemon`""
    $game = Start-RedirectedProcess 'cmd.exe' $gameArgs $fabric
    $gameOut = $game.StandardOutput.ReadToEndAsync()
    $gameErr = $game.StandardError.ReadToEndAsync()

    $gameLog = Join-Path $gameRun 'logs\latest.log'
    $registrationDeadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        if ($game.HasExited) { throw "Fabric Runtime GameTest exited before companion registration ($($game.ExitCode))." }
        if ([DateTime]::UtcNow -gt $registrationDeadline) { throw 'Fabric companion did not register with Runtime in time.' }
        Start-Sleep -Milliseconds 200
        $registrationReady = (Test-Path -LiteralPath $gameLog) -and
            ((Get-Content -Raw -LiteralPath $gameLog) -match 'Runtime bridge connected') -and
            ((Get-Content -Raw -LiteralPath $gameLog) -match 'companion_created')
    } until ($registrationReady)
    Start-Sleep -Milliseconds 500

    foreach ($command in @('follow first', 'pause first', 'resume first', 'stop first')) {
        $runtime.StandardInput.WriteLine($command)
        $runtime.StandardInput.Flush()
        Start-Sleep -Milliseconds 250
    }

    if (-not $game.WaitForExit(90000)) { throw 'Fabric Runtime GameTest did not exit in time.' }
    $runtime.StandardInput.WriteLine('quit')
    $runtime.StandardInput.Flush()
    if (-not $runtime.WaitForExit(15000)) { throw 'Runtime did not shut down after quit.' }

    $gameStdout = $gameOut.GetAwaiter().GetResult()
    $gameStderr = $gameErr.GetAwaiter().GetResult()
    $runtimeStdout = $runtimeOut.GetAwaiter().GetResult()
    $runtimeStderr = $runtimeErr.GetAwaiter().GetResult()
    New-Item -ItemType Directory -Force -Path (Join-Path $runtimeHome 'evidence') | Out-Null
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\fabric-gametest.out.log'), $gameStdout, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\fabric-gametest.err.log'), $gameStderr, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\runtime-cli.out.log'), $runtimeStdout, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\runtime-cli.err.log'), $runtimeStderr, [Text.UTF8Encoding]::new($false))

    if ($game.ExitCode -ne 0) { throw "Fabric GameTest failed with exit code $($game.ExitCode)." }
    if ($runtime.ExitCode -ne 0) { throw "Runtime failed with exit code $($runtime.ExitCode)." }
    if ($gameStdout -notmatch 'Runtime bridge connected') { throw 'Fabric log has no successful Runtime handshake.' }
    if ($gameStdout -notmatch 'All [0-9]+ required tests passed') { throw 'Fabric lifecycle GameTest did not pass.' }
    if ($runtimeStdout -notmatch 'COMMAND_DISPATCHED') { throw 'Runtime CLI did not dispatch a behavior command.' }
    if ($runtimeStdout -notmatch '"state":"CANCELLED"|Cancel command dispatched') {
        throw 'Runtime CLI did not complete the cancellation path.'
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
