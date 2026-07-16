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
$gameOutFile = Join-Path $runtimeHome 'fabric-gametest.out.log'
$gameErrFile = Join-Path $runtimeHome 'fabric-gametest.err.log'

if (-not (Test-Path -LiteralPath $runtimeBat)) {
    throw 'Runtime distribution is missing; run :runtime:runtime-app:installDist first.'
}
if (Test-Path -LiteralPath $runtimeHome) {
    Remove-Item -LiteralPath $runtimeHome -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runtimeHome | Out-Null
$env:MCAC_E2E_REPLAY_TOKEN = 'local-replay-fixture'
$env:MCAC_E2E_BRAIN_TOKEN = 'local-hermes-replay-fixture'
@"
server:
  bind: 127.0.0.1
  port: 8766
  management_port: 18766
  profile_id: e2e
  instance_id: fabric-e2e
  token_file: ./data/pairing.token
  heartbeat_seconds: 15
  allow_remote: false
database:
  path: ./data/companion.db
provider:
  mode: openai-compatible
  base_url: http://127.0.0.1:18767/v1
  api_key_env: MCAC_E2E_REPLAY_TOKEN
  model: local-replay
  timeout_seconds: 10
  max_output_tokens: 1400
  max_calls_per_minute: 30
  max_concurrent: 2
  max_retries: 0
brain:
  mode: hermes
  endpoint: http://127.0.0.1:18768
  token_env: MCAC_E2E_BRAIN_TOKEN
  model: replay
  timeout_seconds: 10
  max_output_tokens: 1400
  max_tool_calls_per_turn: 8
logging:
  file: ./logs/runtime.log
  console: true
"@ | Set-Content -LiteralPath $config -Encoding UTF8

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
    $stream = [IO.FileStream]::new($path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
    try {
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $stream.Dispose()
    }
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
    $snapshot = $null
    $lastInspectionError = $null
    do {
        try {
            $snapshot = Invoke-RestMethod -Uri "http://127.0.0.1:18766/tasks/$taskId" -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 3
            $lastInspectionError = $null
            if ($snapshot.task.state -eq $expected) { return $snapshot }
            if ($snapshot.task.state -in @('COMPLETED', 'FAILED', 'CANCELLED') -and $snapshot.task.state -ne $expected) {
                throw "Runtime task entered $($snapshot.task.state) while waiting for $expected."
            }
        } catch {
            if ($_.Exception.Message -match '^Runtime task entered ') { throw }
            $lastInspectionError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    $lastState = if ($snapshot) { $snapshot.task.state } else { 'unavailable' }
    $transport = if ($lastInspectionError) { "; lastInspectionError=$lastInspectionError" } else { '' }
    throw "Runtime task did not reach $expected in time (last=$lastState$transport)."
}

function Invoke-AgentRequest([string]$pairingToken, [string]$companionId, [string]$text) {
    $body = @{
        commandId = "e2e-agent-$([Guid]::NewGuid())"
        companionId = $companionId
        text = $text
        execute = $true
    } | ConvertTo-Json -Compress -Depth 10
    return Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18766/agent' -Headers @{
        Authorization = "Bearer $pairingToken"
    } -ContentType 'application/json' -Body $body -TimeoutSec 15
}

function Invoke-ExternalBrainRequest([string]$pairingToken, [string]$companionId, [string]$text) {
    $body = @{
        controllerId = 'runtime-primary'
        companionId = $companionId
        text = $text
    } | ConvertTo-Json -Compress -Depth 10
    return Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18766/brain' -Headers @{
        Authorization = "Bearer $pairingToken"
    } -ContentType 'application/json' -Body $body -TimeoutSec 60
}

function Wait-WaitingQuestion([string]$pairingToken, [string]$companionId) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $lastInspectionError = $null
    do {
        try {
            $conversation = Invoke-RestMethod -Uri "http://127.0.0.1:18766/conversations?companionId=$companionId" -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 3
            $lastInspectionError = $null
            if ($conversation.waitingQuestion) { return $conversation }
        } catch {
            $lastInspectionError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    $transport = if ($lastInspectionError) { " Last inspection error: $lastInspectionError" } else { '' }
    throw "Runtime did not persist a waiting question after the verified shortage.$transport"
}

function Wait-AgentPlan(
    [string]$pairingToken,
    [string]$planId,
    [string]$expectedState,
    [int]$minimumPlanningRevision,
    [string]$expectedCapability
) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $snapshot = $null
    $lastInspectionError = $null
    do {
        try {
            $snapshot = Invoke-RestMethod -Uri "http://127.0.0.1:18766/plans/$planId" -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 3
            $lastInspectionError = $null
            $plan = $snapshot.plan
            $current = @($plan.steps | Where-Object { $_.index -eq $plan.currentStep })[0]
            if ($plan.state -eq $expectedState -and $plan.planningRevision -ge $minimumPlanningRevision -and
                ($expectedState -ne 'RUNNING' -or $current.taskId) -and
                (-not $expectedCapability -or $current.definition.capability -eq $expectedCapability)) {
                return $snapshot
            }
        } catch {
            $lastInspectionError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    $lastState = if ($snapshot) { $snapshot.plan.state } else { 'unavailable' }
    $transport = if ($lastInspectionError) { "; lastInspectionError=$lastInspectionError" } else { '' }
    throw "Plan $planId did not reach state=$expectedState revision>=$minimumPlanningRevision capability=$expectedCapability (last=$lastState$transport)."
}

$runtime = $null
$game = $null
$provider = $null
$brainProvider = $null
$runtimeOut = $null
$runtimeErr = $null
$providerOut = $null
$providerErr = $null
$brainProviderOut = $null
$brainProviderErr = $null
$commandEvidence = @()
$conversationEvidence = $null
$goalModificationEvidence = $null
$externalBrainEvidence = $null
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
        'runtime_e2e_ready companion=([0-9a-f-]{36}) chest=(-?\d+),(-?\d+),(-?\d+)')
    if (-not $readyMatch.Success) { throw 'Runtime E2E readiness marker has no companion id.' }
    $companionId = $readyMatch.Groups[1].Value
    $chestX = [int]$readyMatch.Groups[2].Value
    $chestY = [int]$readyMatch.Groups[3].Value
    $chestZ = [int]$readyMatch.Groups[4].Value

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

    Write-Output '[runtime-e2e] behavior controls passed; starting replay-backed shortage conversation'
    $providerScript = Join-Path $root 'tools\provider-replay-server.ps1'
    $providerArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$providerScript`" -X $chestX -Y $chestY -Z $chestZ"
    $provider = Start-TestProcess 'powershell.exe' $providerArgs $root $true
    $providerOut = $provider.StandardOutput.ReadToEndAsync()
    $providerErr = $provider.StandardError.ReadToEndAsync()
    Start-Sleep -Milliseconds 500
    if ($provider.HasExited) { throw "Replay provider exited before planning ($($provider.ExitCode))." }

    Write-Output '[runtime-e2e] exercising in-flight owner goal modification'
    $probePlan = Invoke-AgentRequest $pairingToken $companionId 'Start the modification probe target'
    if (-not $probePlan.accepted -or -not $probePlan.planId) { throw 'Goal-modification probe plan was rejected.' }
    $probeRunning = Wait-AgentPlan $pairingToken $probePlan.planId 'RUNNING' 0 'NavigateTo'
    $probeTaskId = @($probeRunning.plan.steps | Where-Object { $_.index -eq $probeRunning.plan.currentStep })[0].taskId
    $null = Wait-RuntimeTaskState $pairingToken $probeTaskId 'RUNNING'
    # Freeze the unfinished probe through the real control path. This removes the accelerated
    # GameTest tick rate from the goal-modification race while preserving an active original task.
    $pauseProbe = Invoke-RuntimeCommand $pairingToken $companionId 'STOP' @{ action = 'pause' } 'pause modification probe'
    if (-not $pauseProbe.accepted) { throw "Goal-modification probe could not be paused: $($pauseProbe.code)" }
    $probePaused = Wait-RuntimeTaskState $pairingToken $probeTaskId 'PAUSED'
    $modified = Invoke-AgentRequest $pairingToken $companionId 'Change goal and follow owner instead'
    if (-not $modified.accepted -or -not $modified.goalModified -or $modified.planId -ne $probePlan.planId) {
        throw 'Owner goal modification did not revise the original plan id.'
    }
    $modifiedRunning = Wait-AgentPlan $pairingToken $probePlan.planId 'RUNNING' 1 'FollowOwner'
    $cancelModified = Invoke-RuntimeCommand $pairingToken $companionId 'STOP' @{ action = 'cancel' } 'cancel modified probe'
    if (-not $cancelModified.accepted) { throw 'Modified follow plan could not be cancelled before Brain scenario.' }
    $modifiedCancelled = Wait-AgentPlan $pairingToken $probePlan.planId 'CANCELLED' 1 ''
    $goalModificationEvidence = [pscustomobject]@{
        initial = $probePlan; paused = $probePaused; modification = $modified
        running = $modifiedRunning; cancelled = $modifiedCancelled
    }
    Write-Output '[runtime-e2e] same-plan target modification activated and cancelled safely'

    Write-Output '[runtime-e2e] starting Hermes replay and exercising asynchronous External Brain chain'
    $brainScript = Join-Path $root 'tools\hermes-replay-server.ps1'
    $brainArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$brainScript`" -X $chestX -Y $chestY -Z $chestZ"
    $brainProvider = Start-TestProcess 'powershell.exe' $brainArgs $root $true
    $brainProviderOut = $brainProvider.StandardOutput.ReadToEndAsync()
    $brainProviderErr = $brainProvider.StandardError.ReadToEndAsync()
    Start-Sleep -Milliseconds 500
    if ($brainProvider.HasExited) { throw "Hermes replay exited before the Brain turn ($($brainProvider.ExitCode))." }

    $brainQuestion = Invoke-ExternalBrainRequest $pairingToken $companionId 'Bring me 16 iron ingots from that chest.'
    if (-not $brainQuestion.accepted -or $brainQuestion.result.kind -ne 'ASK_USER' -or
        -not $brainQuestion.result.question.questionId -or
        $brainQuestion.result.question.brainSessionId -ne $brainQuestion.result.sessionId -or
        @($brainQuestion.result.question.options | Where-Object { $_.id -eq 'deliver_partial' }).Count -ne 1) {
        throw 'External Brain did not persist the bounded 6/16 shortage question.'
    }
    $waitingConversation = Wait-WaitingQuestion $pairingToken $companionId
    if ($waitingConversation.waitingQuestion.questionId -ne $brainQuestion.result.question.questionId) {
        throw 'Conversation inspection did not return the same persisted Brain question.'
    }
    $brainReply = Invoke-ExternalBrainRequest $pairingToken $companionId 'deliver_partial'
    if (-not $brainReply.accepted -or $brainReply.result.kind -ne 'FINAL_RESPONSE') {
        throw "External Brain did not reach a final response: $($brainReply.code) $($brainReply.result.kind)"
    }
    if ($brainReply.result.sessionId -ne $brainQuestion.result.sessionId) {
        throw 'External Brain answer opened a competing session instead of resuming the original.'
    }
    $brainTools = @($brainReply.result.toolResults)
    $expectedBrainTools = @('movement.navigate', 'inventory.withdraw', 'movement.return', 'inventory.deliver')
    if ($brainTools.Count -ne $expectedBrainTools.Count) { throw 'External Brain did not execute exactly four tools.' }
    for ($index = 0; $index -lt $expectedBrainTools.Count; $index++) {
        if ($brainTools[$index].toolName -ne $expectedBrainTools[$index] -or
            -not $brainTools[$index].terminal -or -not $brainTools[$index].success -or
            $brainTools[$index].observation.state -ne 'SUCCEEDED' -or
            -not $brainTools[$index].observation.fabricObservation) {
            throw "External Brain tool $index lacked its terminal Fabric observation."
        }
    }
    $brainAudit = Invoke-RestMethod -Uri "http://127.0.0.1:18766/brain/audit?companionId=$companionId" -Headers @{
        Authorization = "Bearer $pairingToken"
    } -TimeoutSec 5
    $externalBrainEvidence = [pscustomobject]@{
        question = $brainQuestion; waitingConversation = $waitingConversation
        reply = $brainReply; audit = $brainAudit
    }
    Write-Output '[runtime-e2e] External Brain resumed its ASK_USER session and completed navigate, withdraw, return, and deliver'

    if (-not $game.WaitForExit(90000)) { throw 'Fabric Runtime GameTest did not exit in time.' }
    Write-Output '[runtime-e2e] Fabric GameTest exited; stopping Runtime'
    $runtime.StandardInput.WriteLine('quit')
    $runtime.StandardInput.Flush()
    if (-not $runtime.WaitForExit(15000)) {
        $runtime.Kill()
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
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\external-brain-chain.json'),
        ($externalBrainEvidence | ConvertTo-Json -Depth 40), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\goal-modification.json'),
        ($goalModificationEvidence | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
    if ($provider) {
        if (-not $provider.HasExited) {
            $provider.Kill()
            $null = $provider.WaitForExit(5000)
        }
        [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\provider-replay.out.log'),
            $providerOut.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\provider-replay.err.log'),
            $providerErr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    if ($brainProvider) {
        if (-not $brainProvider.HasExited) {
            $brainProvider.Kill()
            $null = $brainProvider.WaitForExit(5000)
        }
        [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\hermes-replay.out.log'),
            $brainProviderOut.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\hermes-replay.err.log'),
            $brainProviderErr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }

    if ($game.ExitCode -ne 0) { throw "Fabric GameTest failed with exit code $($game.ExitCode)." }
    if ($runtime.ExitCode -ne 0) { throw "Runtime failed with exit code $($runtime.ExitCode)." }
    if ($gameStdout -notmatch 'Runtime bridge connected') { throw 'Fabric log has no successful Runtime handshake.' }
    if ($gameStdout -notmatch 'All [0-9]+ required tests passed') { throw 'Fabric lifecycle GameTest did not pass.' }
    if ($commandEvidence.Count -ne 4 -or $commandEvidence[-1].snapshot.task.state -ne 'CANCELLED') {
        throw 'Runtime command evidence did not complete the safe cancellation path.'
    }
    if ($gameStdout -notmatch 'runtime_e2e_conversation_complete.*delivered=6') {
        throw 'Fabric did not verify the final six-item External Brain delivery.'
    }
    if (-not $externalBrainEvidence -or $externalBrainEvidence.reply.result.toolResults.Count -ne 4) {
        throw 'External Brain evidence did not retain the four terminal tool observations.'
    }
    if (-not $goalModificationEvidence -or
        $goalModificationEvidence.modification.planId -ne $goalModificationEvidence.initial.planId -or
        $goalModificationEvidence.running.plan.steps[$goalModificationEvidence.running.plan.currentStep].definition.capability -ne 'FollowOwner') {
        throw 'Goal modification evidence did not activate the revised capability on the original plan.'
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
    Write-Output 'Runtime/Fabric E2E passed: External Brain 6/16 ASK_USER, same-session answer, navigate, withdraw, return, deliver, and final reply from verified observations.'
} catch {
    foreach ($process in @($game, $provider, $brainProvider, $runtime)) {
        if ($process -and -not $process.HasExited) {
            $process.Kill()
            $null = $process.WaitForExit(5000)
        }
    }
    $failureEvidence = Join-Path $runtimeHome 'evidence'
    New-Item -ItemType Directory -Force -Path $failureEvidence | Out-Null
    [IO.File]::WriteAllText((Join-Path $failureEvidence 'fabric-gametest.out.log'),
        (Read-ProcessLog $gameOutFile), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $failureEvidence 'fabric-gametest.err.log'),
        (Read-ProcessLog $gameErrFile), [Text.UTF8Encoding]::new($false))
    if ($runtimeOut) {
        [IO.File]::WriteAllText((Join-Path $failureEvidence 'runtime-cli.out.log'),
            $runtimeOut.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    if ($runtimeErr) {
        [IO.File]::WriteAllText((Join-Path $failureEvidence 'runtime-cli.err.log'),
            $runtimeErr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    if ($providerOut) {
        [IO.File]::WriteAllText((Join-Path $failureEvidence 'provider-replay.out.log'),
            $providerOut.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    if ($providerErr) {
        [IO.File]::WriteAllText((Join-Path $failureEvidence 'provider-replay.err.log'),
            $providerErr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    if ($brainProviderOut) {
        [IO.File]::WriteAllText((Join-Path $failureEvidence 'hermes-replay.out.log'),
            $brainProviderOut.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    if ($brainProviderErr) {
        [IO.File]::WriteAllText((Join-Path $failureEvidence 'hermes-replay.err.log'),
            $brainProviderErr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    }
    $failurePath = Join-Path $runtimeHome 'e2e-failure.txt'
    $failureText = $_.Exception.ToString() + [Environment]::NewLine + $_.ScriptStackTrace
    [IO.File]::WriteAllText($failurePath, $failureText, [Text.UTF8Encoding]::new($false))
    throw
} finally {
    if ($game -and -not $game.HasExited) { $game.Kill() }
    if ($provider -and -not $provider.HasExited) { $provider.Kill() }
    if ($brainProvider -and -not $brainProvider.HasExited) { $brainProvider.Kill() }
    if ($runtime -and -not $runtime.HasExited) { $runtime.Kill() }
    Remove-Item -LiteralPath $gameToken -Force -ErrorAction SilentlyContinue
}
