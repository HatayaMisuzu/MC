[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
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

function Invoke-McpTool(
    [string]$pairingToken,
    [string]$companionId,
    [string]$brainSessionId,
    [string]$name,
    [hashtable]$arguments,
    [string]$requestId,
    [bool]$allowToolError = $false
) {
    $body = @{
        jsonrpc = '2.0'
        id = $requestId
        method = 'tools/call'
        params = @{ name = $name; arguments = $arguments }
    } | ConvertTo-Json -Compress -Depth 40
    $reply = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18766/mcp' -Headers @{
        Authorization = "Bearer $pairingToken"
        'MCP-Protocol-Version' = '2025-06-18'
        'X-MCAC-Controller-Id' = 'representative-e2e'
        'X-MCAC-Brain-Session-Id' = $brainSessionId
        'X-MCAC-Companion-Id' = $companionId
    } -ContentType 'application/json' -Body $body -TimeoutSec 20
    if ($reply.error) {
        throw "MCP $name failed: $($reply.error | ConvertTo-Json -Compress -Depth 10)"
    }
    $result = $reply.result.structuredContent
    if (-not $result -or ($result.isError -and -not $allowToolError)) {
        throw "MCP $name returned an error: $($result | ConvertTo-Json -Compress -Depth 20)"
    }
    return $result
}

function Get-McpCallId(
    [string]$controllerId,
    [string]$brainSessionId,
    [string]$companionId,
    [string]$requestId
) {
    $identity = "$controllerId`n$brainSessionId`n$companionId`n$($requestId | ConvertTo-Json -Compress)"
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $hash = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($identity))
    } finally {
        $md5.Dispose()
    }
    $hash[6] = ($hash[6] -band 0x0f) -bor 0x30
    $hash[8] = ($hash[8] -band 0x3f) -bor 0x80
    $hex = -join ($hash | ForEach-Object { $_.ToString('x2') })
    return "mcp-$($hex.Substring(0,8))-$($hex.Substring(8,4))-$($hex.Substring(12,4))-$($hex.Substring(16,4))-$($hex.Substring(20,12))"
}

function Start-McpToolRequest(
    [string]$pairingToken,
    [string]$companionId,
    [string]$brainSessionId,
    [string]$name,
    [hashtable]$arguments,
    [string]$requestId
) {
    $body = @{
        jsonrpc = '2.0'
        id = $requestId
        method = 'tools/call'
        params = @{ name = $name; arguments = $arguments }
    } | ConvertTo-Json -Compress -Depth 40
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(40)
    $request = [Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::Post, 'http://127.0.0.1:18766/mcp')
    $null = $request.Headers.TryAddWithoutValidation('Authorization', "Bearer $pairingToken")
    $null = $request.Headers.TryAddWithoutValidation('MCP-Protocol-Version', '2025-06-18')
    $null = $request.Headers.TryAddWithoutValidation('X-MCAC-Controller-Id', 'representative-e2e')
    $null = $request.Headers.TryAddWithoutValidation('X-MCAC-Brain-Session-Id', $brainSessionId)
    $null = $request.Headers.TryAddWithoutValidation('X-MCAC-Companion-Id', $companionId)
    $request.Content = [Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    return [pscustomobject]@{
        client = $client
        request = $request
        task = $client.SendAsync($request)
        callId = Get-McpCallId 'representative-e2e' $brainSessionId $companionId $requestId
    }
}

function Wait-TaskGraphExecution(
    [string]$pairingToken,
    [string]$companionId,
    [string]$brainSessionId,
    [string]$executionId
) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $inspected = Invoke-McpTool $pairingToken $companionId $brainSessionId 'task_graph.inspect' @{
            executionId = $executionId
        } "inspect-$([Guid]::NewGuid())"
        $state = $inspected.observation.state
        if ($state -in @('SUCCEEDED', 'FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED')) {
            return $inspected
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Task Graph $executionId did not reach a terminal state."
}

function Wait-TaskGraphState(
    [string]$pairingToken,
    [string]$companionId,
    [string]$brainSessionId,
    [string]$executionId,
    [string]$expectedState
) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $last = $null
    do {
        $last = Invoke-McpTool $pairingToken $companionId $brainSessionId 'task_graph.inspect' @{
            executionId = $executionId
        } "state-$([Guid]::NewGuid())"
        if ($last.observation.state -eq $expectedState) { return $last }
        if ($last.observation.state -in @('SUCCEEDED', 'FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED')) {
            throw "Task Graph $executionId reached $($last.observation.state) before $expectedState."
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    $actual = if ($last) { $last.observation.state } else { 'unavailable' }
    throw "Task Graph $executionId did not reach $expectedState (last=$actual)."
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
$priorRuntimeStdout = ''
$priorRuntimeStderr = ''
$providerOut = $null
$providerErr = $null
$brainProviderOut = $null
$brainProviderErr = $null
$commandEvidence = @()
$conversationEvidence = $null
$goalModificationEvidence = $null
$externalBrainEvidence = $null
$representativeTaskGraphEvidence = $null
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

    Write-Output '[runtime-e2e] executing external-client Task Graph over live unknown Registry content'
    $graphBrainSession = "representative-graph-$([Guid]::NewGuid())"
    $graph = @{
        version = 'mcac-task-graph/1'
        id = 'unknown-registry-observation-chain'
        permissions = @('READ_WORLD')
        root = @{
            id = 'root'
            type = 'sequence'
            nodes = @(
                @{
                    id = 'search'
                    type = 'call_tool'
                    tool = 'registry.search'
                    arguments = @{
                        kind = 'ITEM'
                        namespace = 'mcac_registry_fixture'
                        query = 'blue'
                        limit = 8
                    }
                },
                @{
                    id = 'has-result'
                    type = 'if'
                    condition = '${outputs.search.entries.length > 0}'
                    then = @{
                        id = 'describe'
                        type = 'call_tool'
                        tool = 'registry.describe'
                        arguments = @{
                            kind = '${outputs.search.entries[0].kind}'
                            id = '${outputs.search.entries[0].id}'
                        }
                    }
                    else = @{
                        id = 'missing'
                        type = 'fail'
                        code = 'UNKNOWN_NAMESPACE_ITEM_MISSING'
                        message = 'live Registry returned no fixture item'
                    }
                },
                @{
                    id = 'done'
                    type = 'return'
                    value = @{
                        searchSource = '${outputs.search.source}'
                        descriptionSource = '${outputs.describe.source}'
                        entry = '${outputs.describe.entry}'
                    }
                }
            )
        }
    }
    $graphStarted = Invoke-McpTool $pairingToken $companionId $graphBrainSession 'task_graph.execute' @{
        graph = $graph
        provenance = @{
            source = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
            liveModel = $false
        }
    } "graph-$([Guid]::NewGuid())"
    $graphExecutionId = $graphStarted.observation.executionId
    if (-not $graphExecutionId) { throw 'Task Graph did not return an execution id.' }
    $graphTerminal = if ($graphStarted.observation.state -eq 'SUCCEEDED') {
        $graphStarted
    } else {
        Wait-TaskGraphExecution $pairingToken $companionId $graphBrainSession $graphExecutionId
    }
    $graphValue = $graphTerminal.observation.value
    if ($graphTerminal.observation.state -ne 'SUCCEEDED' -or
        $graphValue.searchSource -ne 'LIVE_SERVER_REGISTRY' -or
        $graphValue.descriptionSource -ne 'LIVE_SERVER_REGISTRY' -or
        $graphValue.entry.namespace -ne 'mcac_registry_fixture' -or
        $graphValue.entry.id -notmatch '^mcac_registry_fixture:') {
        throw "Unknown-namespace Task Graph did not return verified live Registry evidence: $($graphTerminal | ConvertTo-Json -Compress -Depth 30)"
    }
    $registryGraphEvidence = [pscustomobject]@{
        classification = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
        liveModel = $false
        started = $graphStarted
        terminal = $graphTerminal
    }
    Write-Output '[runtime-e2e] Task Graph composed live Registry search output into Registry describe'

    Write-Output '[runtime-e2e] executing Observation-to-body-effect Task Graph'
    $effectBrainSession = "representative-effect-$([Guid]::NewGuid())"
    $effectGraph = @{
        version = 'mcac-task-graph/1'
        id = 'observed-block-look'
        inputs = @{
            target = @{ type = 'position'; required = $true }
        }
        permissions = @('READ_WORLD', 'MOVE')
        root = @{
            id = 'root'
            type = 'sequence'
            nodes = @(
                @{
                    id = 'inspect'
                    type = 'call_tool'
                    tool = 'block.inspect'
                    arguments = @{ position = '${inputs.target}' }
                },
                @{
                    id = 'look'
                    type = 'call_tool'
                    tool = 'movement.look'
                    arguments = @{
                        dimension = '${outputs.inspect.position.dimension}'
                        x = '${outputs.inspect.position.x}'
                        y = '${outputs.inspect.position.y}'
                        z = '${outputs.inspect.position.z}'
                    }
                },
                @{
                    id = 'done'
                    type = 'return'
                    value = @{
                        block = '${outputs.inspect.block}'
                        position = '${outputs.inspect.position}'
                        effectState = '${outputs.look.state}'
                        effectEvidence = '${outputs.look.fabricObservation.snapshot.evidence}'
                    }
                }
            )
        }
    }
    $effectStarted = Invoke-McpTool $pairingToken $companionId $effectBrainSession 'task_graph.execute' @{
        graph = $effectGraph
        inputs = @{
            target = @{
                dimension = 'minecraft:overworld'
                x = $chestX
                y = $chestY
                z = $chestZ
            }
        }
        provenance = @{
            source = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
            liveModel = $false
        }
    } "effect-$([Guid]::NewGuid())"
    $effectExecutionId = $effectStarted.observation.executionId
    if (-not $effectExecutionId) { throw 'Observation-to-body-effect graph did not return an execution id.' }
    $effectTerminal = if ($effectStarted.observation.state -eq 'SUCCEEDED') {
        $effectStarted
    } else {
        Wait-TaskGraphExecution $pairingToken $companionId $effectBrainSession $effectExecutionId
    }
    $effectValue = $effectTerminal.observation.value
    if ($effectTerminal.observation.state -ne 'SUCCEEDED' -or
        $effectValue.block -ne 'minecraft:chest' -or
        $effectValue.effectState -ne 'SUCCEEDED' -or
        $effectValue.effectEvidence -notmatch 'VANILLA_ENTITY_LOOK') {
        throw "Observation-to-body-effect graph did not produce a verified primitive effect: $($effectTerminal | ConvertTo-Json -Compress -Depth 30)"
    }
    $effectGraphEvidence = [pscustomobject]@{
        classification = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
        liveModel = $false
        started = $effectStarted
        terminal = $effectTerminal
    }
    Write-Output '[runtime-e2e] exercising external graph correction and deterministic fallback'
    $editedBrainSession = "representative-edit-$([Guid]::NewGuid())"
    $invalidEditedGraph = @{
        version = 'mcac-task-graph/1'
        id = 'externally-edited-fallback'
        permissions = @('READ_WORLD')
        root = @{
            id = 'root'
            type = 'fallback'
            nodes = @(
                @{
                    id = 'declined-primary'
                    type = 'fail'
                    code = 'PRIMARY_DECLINED'
                    message = 'external graph selected its declared fallback'
                },
                @{
                    id = 'describe'
                    type = 'call_tool'
                    tool = 'registry.describe'
                    arguments = @{ kind = 'ITEM' }
                }
            )
        }
    }
    $invalidRevision = Invoke-McpTool $pairingToken $companionId $editedBrainSession 'task_graph.execute' @{
        graph = $invalidEditedGraph
        provenance = @{
            source = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
            liveModel = $false
            revision = 1
        }
    } "invalid-edit-$([Guid]::NewGuid())" $true
    if ($invalidRevision.code -ne 'TASK_GRAPH_INVALID' -or
        $invalidRevision.observation.valid -ne $false -or
        $invalidRevision.observation.issues.code -notcontains 'TOOL_INPUT_SCHEMA_INVALID') {
        throw "Invalid external graph did not expose its Tool schema error: $($invalidRevision | ConvertTo-Json -Compress -Depth 30)"
    }

    $correctedGraph = $invalidEditedGraph.Clone()
    $correctedGraph.root = @{
        id = 'root'
        type = 'sequence'
        nodes = @(
            @{
                id = 'choose'
                type = 'fallback'
                nodes = @(
                    @{
                        id = 'declined-primary'
                        type = 'fail'
                        code = 'PRIMARY_DECLINED'
                        message = 'external graph selected its declared fallback'
                    },
                    @{
                        id = 'describe'
                        type = 'call_tool'
                        tool = 'registry.describe'
                        arguments = @{
                            kind = 'ITEM'
                            id = 'mcac_registry_fixture:blue_block'
                        }
                    }
                )
            },
            @{
                id = 'done'
                type = 'return'
                value = @{
                    source = '${outputs.describe.source}'
                    entry = '${outputs.describe.entry}'
                }
            }
        )
    }
    $correctedStarted = Invoke-McpTool $pairingToken $companionId $editedBrainSession 'task_graph.execute' @{
        graph = $correctedGraph
        provenance = @{
            source = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
            liveModel = $false
            revision = 2
            correctedAfter = 'TOOL_INPUT_SCHEMA_INVALID'
        }
    } "corrected-edit-$([Guid]::NewGuid())"
    $correctedExecutionId = $correctedStarted.observation.executionId
    if (-not $correctedExecutionId) { throw 'Corrected fallback graph did not return an execution id.' }
    $correctedTerminal = if ($correctedStarted.observation.state -eq 'SUCCEEDED') {
        $correctedStarted
    } else {
        Wait-TaskGraphExecution $pairingToken $companionId $editedBrainSession $correctedExecutionId
    }
    if ($correctedTerminal.observation.state -ne 'SUCCEEDED' -or
        $correctedTerminal.observation.value.source -ne 'LIVE_SERVER_REGISTRY' -or
        $correctedTerminal.observation.value.entry.id -ne 'mcac_registry_fixture:blue_block') {
        throw "Corrected fallback graph did not complete from live Registry state: $($correctedTerminal | ConvertTo-Json -Compress -Depth 30)"
    }
    $correctedGraphEvidence = [pscustomobject]@{
        classification = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
        liveModel = $false
        rejectedRevision = $invalidRevision
        started = $correctedStarted
        terminal = $correctedTerminal
    }
    Write-Output '[runtime-e2e] exercising Task Graph ASK_USER ownership and resume'
    $askBrainSession = "representative-ask-$([Guid]::NewGuid())"
    $askGraph = @{
        version = 'mcac-task-graph/1'
        id = 'ask-before-live-query'
        permissions = @('READ_WORLD')
        root = @{
            id = 'root'
            type = 'sequence'
            nodes = @(
                @{
                    id = 'choice'
                    type = 'ask_user'
                    prompt = 'Inspect the selected live Registry item?'
                    options = @('Inspect', 'Stop')
                    freeTextAllowed = $false
                },
                @{
                    id = 'accepted'
                    type = 'if'
                    condition = '${outputs.choice.optionId == "option_1"}'
                    then = @{
                        id = 'described'
                        type = 'call_tool'
                        tool = 'registry.describe'
                        arguments = @{
                            kind = 'ITEM'
                            id = 'mcac_registry_fixture:blue_block'
                        }
                    }
                    else = @{
                        id = 'answer-declined'
                        type = 'fail'
                        code = 'OWNER_DECLINED'
                        message = 'deterministic test owner declined the query'
                    }
                },
                @{
                    id = 'done'
                    type = 'return'
                    value = @{
                        answer = '${outputs.choice}'
                        source = '${outputs.described.source}'
                        entry = '${outputs.described.entry}'
                    }
                }
            )
        }
    }
    $askStarted = Invoke-McpTool $pairingToken $companionId $askBrainSession 'task_graph.execute' @{
        graph = $askGraph
        provenance = @{
            source = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
            liveModel = $false
            deterministicTestAnswer = $true
        }
    } "ask-$([Guid]::NewGuid())"
    $askExecutionId = $askStarted.observation.executionId
    if (-not $askExecutionId) { throw 'ASK_USER graph did not return an execution id.' }
    $askConversation = Wait-WaitingQuestion $pairingToken $companionId
    if ($askConversation.waitingQuestion.taskGraphExecutionId -ne $askExecutionId -or
        $askConversation.waitingQuestion.brainSessionId -or
        $askConversation.waitingQuestion.options[0].id -ne 'option_1') {
        throw "ASK_USER graph did not own the expected durable question: $($askConversation | ConvertTo-Json -Compress -Depth 20)"
    }
    $askAnswer = Invoke-ExternalBrainRequest $pairingToken $companionId 'option_1'
    if (-not $askAnswer.accepted -or $askAnswer.source -ne 'task-graph' -or
        $askAnswer.executionId -ne $askExecutionId) {
        throw "Deterministic user answer did not resume the owning Task Graph: $($askAnswer | ConvertTo-Json -Compress -Depth 20)"
    }
    $askTerminal = Wait-TaskGraphExecution $pairingToken $companionId $askBrainSession $askExecutionId
    if ($askTerminal.observation.state -ne 'SUCCEEDED' -or
        $askTerminal.observation.value.answer.optionId -ne 'option_1' -or
        $askTerminal.observation.value.source -ne 'LIVE_SERVER_REGISTRY' -or
        $askTerminal.observation.value.entry.id -ne 'mcac_registry_fixture:blue_block') {
        throw "ASK_USER graph did not resume into its selected live Tool branch: $($askTerminal | ConvertTo-Json -Compress -Depth 30)"
    }
    $askGraphEvidence = [pscustomobject]@{
        classification = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
        liveModel = $false
        deterministicTestAnswer = $true
        started = $askStarted
        waiting = $askConversation
        answer = $askAnswer
        terminal = $askTerminal
    }
    Write-Output '[runtime-e2e] exercising paused Task Graph across a real Runtime restart'
    $restartBrainSession = "representative-restart-$([Guid]::NewGuid())"
    $restartGraph = @{
        version = 'mcac-task-graph/1'
        id = 'paused-restart-effect'
        permissions = @('READ_WORLD', 'MOVE')
        root = @{
            id = 'root'
            type = 'sequence'
            nodes = @(
                @{
                    id = 'inspectBeforeRestart'
                    type = 'call_tool'
                    tool = 'block.inspect'
                    arguments = @{
                        position = @{
                            dimension = 'minecraft:overworld'
                            x = $chestX
                            y = $chestY
                            z = $chestZ
                        }
                    }
                },
                @{
                    id = 'restartBoundary'
                    type = 'wait'
                    durationMillis = 2500
                },
                @{
                    id = 'lookAfterRestart'
                    type = 'call_tool'
                    tool = 'movement.look'
                    arguments = @{
                        dimension = '${outputs.inspectBeforeRestart.position.dimension}'
                        x = '${outputs.inspectBeforeRestart.position.x}'
                        y = '${outputs.inspectBeforeRestart.position.y}'
                        z = '${outputs.inspectBeforeRestart.position.z}'
                    }
                },
                @{
                    id = 'done'
                    type = 'return'
                    value = @{
                        block = '${outputs.inspectBeforeRestart.block}'
                        effectState = '${outputs.lookAfterRestart.state}'
                        effectEvidence = '${outputs.lookAfterRestart.fabricObservation.snapshot.evidence}'
                    }
                }
            )
        }
    }
    $restartRequestId = "restart-$([Guid]::NewGuid())"
    $restartRequest = Start-McpToolRequest $pairingToken $companionId $restartBrainSession 'task_graph.execute' @{
        graph = $restartGraph
        provenance = @{
            source = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
            liveModel = $false
            runtimeRestarted = $true
        }
    } $restartRequestId
    $restartExecutionId = $restartRequest.callId
    $restartWaiting = Wait-TaskGraphState $pairingToken $companionId $restartBrainSession `
        $restartExecutionId 'WAITING'
    $restartPaused = Invoke-McpTool $pairingToken $companionId $restartBrainSession 'task_graph.pause' @{
        executionId = $restartExecutionId
    } "pause-restart-$([Guid]::NewGuid())"
    if ($restartPaused.observation.state -ne 'PAUSED') {
        throw "Restart graph did not persist PAUSED before shutdown: $($restartPaused | ConvertTo-Json -Compress -Depth 20)"
    }
    try {
        $restartHttpResponse = $restartRequest.task.GetAwaiter().GetResult()
        $restartHttpBody = $restartHttpResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() |
            ConvertFrom-Json
        $restartRequestResult = $restartHttpBody.result.structuredContent
        if ($restartRequestResult.callId -ne $restartExecutionId -or
            $restartRequestResult.observation.state -ne 'PAUSED') {
            throw "Original MCP execution request did not finish at PAUSED: $($restartHttpBody | ConvertTo-Json -Compress -Depth 20)"
        }
    } finally {
        $restartRequest.request.Dispose()
        $restartRequest.client.Dispose()
    }

    $runtime.StandardInput.WriteLine('quit')
    $runtime.StandardInput.Flush()
    if (-not $runtime.WaitForExit(15000)) {
        $runtime.Kill()
        $null = $runtime.WaitForExit(5000)
        throw 'Runtime did not shut down at the representative restart boundary.'
    }
    if ($runtime.ExitCode -ne 0) { throw "Runtime restart-boundary process exited with $($runtime.ExitCode)." }
    $priorRuntimeStdout += $runtimeOut.GetAwaiter().GetResult()
    $priorRuntimeStderr += $runtimeErr.GetAwaiter().GetResult()

    $runtime = Start-TestProcess $java $runtimeArgs $root $true
    $runtimeOut = $runtime.StandardOutput.ReadToEndAsync()
    $runtimeErr = $runtime.StandardError.ReadToEndAsync()
    $reconnectDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $reconnected = $false
    do {
        if ($runtime.HasExited) { throw "Restarted Runtime exited before Fabric reconnected ($($runtime.ExitCode))." }
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18766/health' -Headers @{
                Authorization = "Bearer $pairingToken"
            } -TimeoutSec 2
            $reconnected = $health.onlineCompanionCount -ge 1
        } catch {
            $reconnected = $false
        }
        if (-not $reconnected) { Start-Sleep -Milliseconds 200 }
    } while (-not $reconnected -and [DateTime]::UtcNow -lt $reconnectDeadline)
    if (-not $reconnected) { throw 'Fabric body did not reconnect to the restarted Runtime.' }

    $restartStillPaused = Wait-TaskGraphState $pairingToken $companionId $restartBrainSession `
        $restartExecutionId 'PAUSED'
    $restartResumed = Invoke-McpTool $pairingToken $companionId $restartBrainSession 'task_graph.resume' @{
        executionId = $restartExecutionId
    } "resume-restart-$([Guid]::NewGuid())"
    if (-not $restartResumed.success -and $restartResumed.code -notin @('RESUME_ACCEPTED', 'RECOVERY_RESUME_ACCEPTED')) {
        throw "Restart graph resume was rejected: $($restartResumed | ConvertTo-Json -Compress -Depth 20)"
    }
    $restartTerminal = Wait-TaskGraphExecution $pairingToken $companionId $restartBrainSession $restartExecutionId
    if ($restartTerminal.observation.state -ne 'SUCCEEDED' -or
        $restartTerminal.observation.value.block -ne 'minecraft:chest' -or
        $restartTerminal.observation.value.effectState -ne 'SUCCEEDED' -or
        $restartTerminal.observation.value.effectEvidence -notmatch 'VANILLA_ENTITY_LOOK') {
        throw "Restart graph did not continue into its verified primitive effect: $($restartTerminal | ConvertTo-Json -Compress -Depth 30)"
    }
    $restartGraphEvidence = [pscustomobject]@{
        classification = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
        liveModel = $false
        runtimeRestarted = $true
        requestResult = $restartRequestResult
        waiting = $restartWaiting
        pausedBeforeShutdown = $restartPaused
        pausedAfterRestart = $restartStillPaused
        resumed = $restartResumed
        terminal = $restartTerminal
    }
    $representativeTaskGraphEvidence = [pscustomobject]@{
        classification = 'LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E'
        liveModel = $false
        graphs = @($registryGraphEvidence, $effectGraphEvidence, $correctedGraphEvidence, $askGraphEvidence,
            $restartGraphEvidence)
    }
    Write-Output '[runtime-e2e] Task Graph inspected a live block and aimed the body at its observed position'
    Write-Output '[runtime-e2e] External client corrected a rejected graph and executed its declared fallback'
    Write-Output '[runtime-e2e] Task Graph resumed its exact ASK_USER execution and ran the selected live Tool'
    Write-Output '[runtime-e2e] Paused Task Graph survived Runtime restart and completed its primitive effect'

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
    $runtimeStdout = $priorRuntimeStdout + $runtimeOut.GetAwaiter().GetResult()
    $runtimeStderr = $priorRuntimeStderr + $runtimeErr.GetAwaiter().GetResult()
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
    [IO.File]::WriteAllText((Join-Path $runtimeHome 'evidence\representative-task-graph.json'),
        ($representativeTaskGraphEvidence | ConvertTo-Json -Depth 40), [Text.UTF8Encoding]::new($false))
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
    if (-not $representativeTaskGraphEvidence -or
        $representativeTaskGraphEvidence.graphs.Count -ne 5 -or
        ($representativeTaskGraphEvidence.graphs | Where-Object {
            $_.terminal.observation.state -ne 'SUCCEEDED' -or $_.liveModel
        }).Count -ne 0 -or
        $representativeTaskGraphEvidence.graphs[2].rejectedRevision.code -ne 'TASK_GRAPH_INVALID' -or
        -not $representativeTaskGraphEvidence.graphs[3].deterministicTestAnswer -or
        -not $representativeTaskGraphEvidence.graphs[4].runtimeRestarted -or
        $representativeTaskGraphEvidence.liveModel) {
        throw 'Representative Task Graph evidence is missing or has an invalid verification classification.'
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
