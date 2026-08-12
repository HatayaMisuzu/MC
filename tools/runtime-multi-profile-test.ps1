[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReleaseDir)

$ErrorActionPreference='Stop'
$testHome=Join-Path (Split-Path $ReleaseDir -Parent) 'runtime-multi-profile-test'
Add-Type -AssemblyName System.Net.Http

if(Test-Path -LiteralPath $testHome){
    Remove-Item -LiteralPath $testHome -Recurse -Force
}
New-Item -ItemType Directory -Path $testHome | Out-Null

function New-Profile([string]$id,[int]$port){
    $dir=Join-Path $testHome $id
    New-Item -ItemType Directory -Path $dir | Out-Null
@"
server:
  bind: 127.0.0.1
  port: $port
  management_port: $($port + 10000)
  profile_id: "$id"
  instance_id: "$id"
  token_file: ./pairing.token
  heartbeat_seconds: 15
  allow_remote: false
database:
  path: ./companion.db
provider:
  mode: rules
  base_url: "https://api.openai.com"
  api_key_env: MC_COMPANION_API_KEY
  model: "disabled"
  timeout_seconds: 60
logging:
  file: ./runtime.log
  console: false
"@ | Set-Content -LiteralPath (Join-Path $dir 'runtime.yml') -Encoding utf8

    return @{
        Dir=$dir
        Token=$null
        TokenPath=Join-Path $dir 'pairing.token'
        Id=$id
        Port=$port
        Stdout=Join-Path $dir 'runtime.stdout.log'
        Stderr=Join-Path $dir 'runtime.stderr.log'
    }
}

function Tail([string]$path){
    if(Test-Path -LiteralPath $path){
        return (Get-Content -LiteralPath $path -Tail 100 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
    }
    return '<missing>'
}

function Assert-Alive($process,$profile){
    if($process -and $process.HasExited){
        throw "Runtime profile '$($profile.Id)' exited before acceptance. Exit=$($process.ExitCode)`nSTDOUT:`n$(Tail $profile.Stdout)`nSTDERR:`n$(Tail $profile.Stderr)`nRUNTIME:`n$(Tail (Join-Path $profile.Dir 'runtime.log'))"
    }
}

function Try-LoadRuntimeGeneratedToken($profile){
    if($profile.Token){return $true}
    if(-not (Test-Path -LiteralPath $profile.TokenPath -PathType Leaf)){return $false}

    $token=[IO.File]::ReadAllText($profile.TokenPath,[Text.Encoding]::ASCII).Trim()
    if($token -notmatch '^[A-Za-z0-9_-]{32,128}$'){
        throw "Runtime generated an invalid pairing token for profile '$($profile.Id)'"
    }
    $profile.Token=$token
    return $true
}

function Health-Snapshot($profile){
    if(-not $profile.Token){throw "Profile '$($profile.Id)' has no generated token yet"}
    Invoke-RestMethod -UseBasicParsing -TimeoutSec 2 `
        -Headers @{Authorization="Bearer $($profile.Token)"} `
        -Uri "http://127.0.0.1:$($profile.Port + 10000)/health"
}

function Health($profile){
    if(-not $profile.Token){return $false}
    try{
        $result=Health-Snapshot $profile
        return $result.profileId -eq $profile.Id -and
            $result.instanceId -eq $profile.Id -and
            $result.protocolVersion -eq 'mc-companion/1' -and
            $result.port -eq $profile.Port -and
            $result.taskGraph.status -eq 'READY'
    }catch{
        return $false
    }
}

function Write-FailureSummary($a,$b,$pa,$pb,[string]$reason){
    $summary=[ordered]@{
        reason=$reason
        generatedAt=[DateTimeOffset]::UtcNow.ToString('o')
        profiles=@()
    }
    foreach($pair in @(@($a,$pa),@($b,$pb))){
        $profile=$pair[0]
        $process=$pair[1]
        $summary.profiles += [ordered]@{
            id=$profile.Id
            port=$profile.Port
            tokenFileCreated=(Test-Path -LiteralPath $profile.TokenPath -PathType Leaf)
            processId=if($process){$process.Id}else{$null}
            exited=if($process){$process.HasExited}else{$null}
            exitCode=if($process -and $process.HasExited){$process.ExitCode}else{$null}
            stdout=$profile.Stdout
            stderr=$profile.Stderr
            runtimeLog=(Join-Path $profile.Dir 'runtime.log')
        }
    }
    $summary | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $testHome 'failure-summary.json') -Encoding utf8
}

function New-McpSession($profile){
    $client=[Net.Http.HttpClient]::new()
    $client.Timeout=[TimeSpan]::FromSeconds(10)
    try{
        $request=[Net.Http.HttpRequestMessage]::new(
            [Net.Http.HttpMethod]::Post,
            "http://127.0.0.1:$($profile.Port + 10000)/mcp")
        $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$profile.Token)
        $request.Headers.Add('X-MCAC-Companion-Id',"telemetry-$($profile.Id)")
        $request.Headers.Add('X-MCAC-Brain-Session-Id',"telemetry-$($profile.Id)")
        $body=@{
            jsonrpc='2.0'
            id="init-$($profile.Id)"
            method='initialize'
            params=@{
                protocolVersion='2025-06-18'
                capabilities=@{}
                clientInfo=@{name='mcac-multi-profile-test';version='test'}
            }
        } | ConvertTo-Json -Depth 8 -Compress
        $request.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        try{
            $content=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if(-not $response.IsSuccessStatusCode){throw "MCP initialize failed: $content"}
            return [string]($response.Headers.GetValues('Mcp-Session-Id') | Select-Object -First 1)
        }finally{
            $response.Dispose()
            $request.Dispose()
        }
    }finally{
        $client.Dispose()
    }
}

function Start-Wait($profile){
    $client=[Net.Http.HttpClient]::new()
    $client.Timeout=[TimeSpan]::FromSeconds(10)
    $session=New-McpSession $profile
    $request=[Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::Post,
        "http://127.0.0.1:$($profile.Port + 10000)/mcp")
    $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$profile.Token)
    $request.Headers.Add('MCP-Protocol-Version','2025-06-18')
    $request.Headers.Add('Mcp-Session-Id',$session)
    $request.Headers.Add('X-MCAC-Companion-Id',"telemetry-$($profile.Id)")
    $request.Headers.Add('X-MCAC-Brain-Session-Id',"telemetry-$($profile.Id)")
    $body=@{
        jsonrpc='2.0'
        id="wait-$($profile.Id)"
        method='tools/call'
        params=@{
            name='task_graph.execute'
            arguments=@{
                graph=@{
                    version='mcac-task-graph/1'
                    id="multi-profile-telemetry-$($profile.Id)"
                    permissions=@()
                    root=@{id='wait';type='wait';durationMillis=3000}
                }
                provenance=@{source='LOCAL_MULTI_PROFILE_TELEMETRY_TEST';liveModel=$false}
            }
        }
    } | ConvertTo-Json -Depth 12 -Compress
    $request.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
    return @{Client=$client;Request=$request;Task=$client.SendAsync($request);Profile=$profile;Session=$session}
}

function Inspect-Wait($pending,[string]$executionId){
    $request=[Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::Post,
        "http://127.0.0.1:$($pending.Profile.Port + 10000)/mcp")
    try{
        $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new(
            'Bearer',$pending.Profile.Token)
        $request.Headers.Add('MCP-Protocol-Version','2025-06-18')
        $request.Headers.Add('Mcp-Session-Id',$pending.Session)
        $request.Headers.Add('X-MCAC-Companion-Id',"telemetry-$($pending.Profile.Id)")
        $request.Headers.Add('X-MCAC-Brain-Session-Id',"telemetry-$($pending.Profile.Id)")
        $body=@{
            jsonrpc='2.0'
            id="inspect-$([Guid]::NewGuid().ToString('N'))"
            method='tools/call'
            params=@{
                name='task_graph.inspect'
                arguments=@{executionId=$executionId}
            }
        } | ConvertTo-Json -Depth 8 -Compress
        $request.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
        $response=$pending.Client.SendAsync($request).GetAwaiter().GetResult()
        try{
            $json=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if(-not $response.IsSuccessStatusCode -or $json.result.isError){
                throw "Task Graph inspect failed: $($json | ConvertTo-Json -Depth 12 -Compress)"
            }
            return $json.result.structuredContent
        }finally{
            $response.Dispose()
        }
    }finally{
        $request.Dispose()
    }
}

function Complete-Wait($pending){
    try{
        $response=$pending.Task.GetAwaiter().GetResult()
        try{
            $json=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if(-not $response.IsSuccessStatusCode -or
                $json.result.isError -or
                $json.result.structuredContent.code -ne 'TASK_GRAPH_ACCEPTED'){
                throw "Task Graph wait failed: $($json | ConvertTo-Json -Depth 12 -Compress)"
            }
            $executionId=$json.result.structuredContent.observation.executionId
        }finally{
            $response.Dispose()
        }
        $deadline=[DateTime]::UtcNow.AddSeconds(10)
        do{
            $inspected=Inspect-Wait $pending $executionId
            $state=$inspected.observation.state
            if($state -eq 'SUCCEEDED'){return}
            if($state -in @('FAILED','CANCELLED','RECONCILIATION_REQUIRED')){
                throw "Task Graph wait reached unexpected terminal state: $state"
            }
            Start-Sleep -Milliseconds 100
        }while([DateTime]::UtcNow -lt $deadline)
        throw "Task Graph wait did not reach authoritative SUCCEEDED state: $executionId"
    }finally{
        $pending.Request.Dispose()
        $pending.Client.Dispose()
    }
}

$a=New-Profile a 8766
$b=New-Profile b 8767
$pa=$null
$pb=$null

try{
    $pa=Start-Process -FilePath (Join-Path $ReleaseDir 'runtime-app.exe') `
        -ArgumentList '--config runtime.yml --no-cli' `
        -WorkingDirectory $a.Dir -WindowStyle Hidden `
        -RedirectStandardOutput $a.Stdout -RedirectStandardError $a.Stderr -PassThru
    $pb=Start-Process -FilePath (Join-Path $ReleaseDir 'runtime-app.exe') `
        -ArgumentList '--config runtime.yml --no-cli' `
        -WorkingDirectory $b.Dir -WindowStyle Hidden `
        -RedirectStandardOutput $b.Stdout -RedirectStandardError $b.Stderr -PassThru

    # Each Runtime may legally spend up to 15 seconds starting WebSocket before management startup.
    $limit=[DateTime]::UtcNow.AddSeconds(45)
    $healthy=$false
    while([DateTime]::UtcNow -lt $limit){
        Assert-Alive $pa $a
        Assert-Alive $pb $b
        [void](Try-LoadRuntimeGeneratedToken $a)
        [void](Try-LoadRuntimeGeneratedToken $b)
        if((Health $a) -and (Health $b)){
            $healthy=$true
            break
        }
        Start-Sleep -Milliseconds 200
    }

    Assert-Alive $pa $a
    Assert-Alive $pb $b
    if(-not $healthy){
        throw 'Two Runtime profiles did not create secure tokens and report matching authenticated health within the bounded startup window'
    }

    $waitA=Start-Wait $a
    $waitB=Start-Wait $b
    $telemetryLimit=[DateTime]::UtcNow.AddSeconds(5)
    $visible=$false
    do{
        Start-Sleep -Milliseconds 100
        $healthA=Health-Snapshot $a
        $healthB=Health-Snapshot $b
        $visible=$healthA.taskGraph.timedWaits -eq 1 -and
            $healthB.taskGraph.timedWaits -eq 1 -and
            $healthA.taskGraph.durable.states.WAITING -eq 1 -and
            $healthB.taskGraph.durable.states.WAITING -eq 1
    }while(-not $visible -and [DateTime]::UtcNow -lt $telemetryLimit)

    if(-not $visible){throw 'Both profile-local Task Graph waits were not visible in telemetry'}
    Complete-Wait $waitA
    Complete-Wait $waitB

    $healthA=Health-Snapshot $a
    $healthB=Health-Snapshot $b
    if($healthA.taskGraph.durable.totalExecutions -ne 1 -or
        $healthB.taskGraph.durable.totalExecutions -ne 1 -or
        $healthA.taskGraph.durable.states.SUCCEEDED -ne 1 -or
        $healthB.taskGraph.durable.states.SUCCEEDED -ne 1){
        throw 'Task Graph telemetry crossed profile boundaries or did not retain terminal state'
    }

    Stop-Process -Id $pa.Id -Force
    if(-not $pa.WaitForExit(5000)){
        throw 'Runtime profile A did not exit within the five-second cleanup boundary'
    }
    Start-Sleep -Milliseconds 300
    $survivor=Health-Snapshot $b
    if(-not (Health $b) -or $survivor.taskGraph.durable.totalExecutions -ne 1){
        throw 'Stopping profile A affected profile B or its Task Graph telemetry'
    }

    Write-Output 'Runtime multi-profile test passed: Runtime-created owner-only tokens, isolated authenticated identity and Task Graph telemetry; stop A left B healthy.'
}catch{
    Write-FailureSummary $a $b $pa $pb $_.Exception.Message
    throw
}finally{
    foreach($process in @($pa,$pb)){
        if($process -and -not $process.HasExited){
            Stop-Process -Id $process.Id -Force
            [void]$process.WaitForExit(5000)
        }
        if($process){$process.Dispose()}
    }
}
