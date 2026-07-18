[CmdletBinding()]param([Parameter(Mandatory=$true)][string]$ReleaseDir)
$ErrorActionPreference='Stop';$testHome=Join-Path (Split-Path $ReleaseDir -Parent) 'runtime-multi-profile-test'
Add-Type -AssemblyName System.Net.Http
if(Test-Path $testHome){Remove-Item -LiteralPath $testHome -Recurse -Force};New-Item -ItemType Directory -Path $testHome|Out-Null
function New-Profile([string]$id,[int]$port){$dir=Join-Path $testHome $id;New-Item -ItemType Directory -Path $dir|Out-Null;$bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();try{$rng.GetBytes($bytes)}finally{$rng.Dispose()};$token=[Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=');Set-Content -LiteralPath (Join-Path $dir 'pairing.token') -Value $token -Encoding ascii;@"
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
"@|Set-Content -LiteralPath (Join-Path $dir 'runtime.yml') -Encoding utf8;return @{Dir=$dir;Token=$token;Id=$id;Port=$port}}
function Port([int]$p){$c=[Net.Sockets.TcpClient]::new();try{$c.Connect('127.0.0.1',$p);return $true}catch{return $false}finally{$c.Dispose()}}
function Health-Snapshot($profile){
    $headers=@{Authorization="Bearer $($profile.Token)"}
    return Invoke-RestMethod -UseBasicParsing -TimeoutSec 2 -Headers $headers `
        -Uri "http://127.0.0.1:$($profile.Port + 10000)/health"
}
function Health($profile){
    try {
        $result=Health-Snapshot $profile
        return $result.profileId -eq $profile.Id -and $result.instanceId -eq $profile.Id -and
            $result.protocolVersion -eq 'mc-companion/1' -and $result.port -eq $profile.Port -and
            $result.taskGraph.status -eq 'READY'
    } catch { return $false }
}
function New-McpSession($profile){
    $client=[Net.Http.HttpClient]::new()
    try {
        $request=[Net.Http.HttpRequestMessage]::new(
            [Net.Http.HttpMethod]::Post,
            "http://127.0.0.1:$($profile.Port + 10000)/mcp")
        $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$profile.Token)
        $request.Headers.Add('X-MCAC-Companion-Id',"telemetry-$($profile.Id)")
        $request.Headers.Add('X-MCAC-Brain-Session-Id',"telemetry-$($profile.Id)")
        $body=@{
            jsonrpc='2.0';id="init-$($profile.Id)";method='initialize'
            params=@{
                protocolVersion='2025-06-18';capabilities=@{}
                clientInfo=@{name='mcac-multi-profile-test';version='test'}
            }
        }|ConvertTo-Json -Depth 8 -Compress
        $request.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $content=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if(-not $response.IsSuccessStatusCode){throw "MCP initialize failed: $content"}
            return [string]($response.Headers.GetValues('Mcp-Session-Id')|Select-Object -First 1)
        } finally {$response.Dispose()}
    } finally {$client.Dispose()}
}
function Start-Graph-Wait($profile){
    $client=[Net.Http.HttpClient]::new()
    $request=[Net.Http.HttpRequestMessage]::new(
        [Net.Http.HttpMethod]::Post,
        "http://127.0.0.1:$($profile.Port + 10000)/mcp")
    $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$profile.Token)
    $request.Headers.Add('MCP-Protocol-Version','2025-06-18')
    $request.Headers.Add('Mcp-Session-Id',(New-McpSession $profile))
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
    }|ConvertTo-Json -Depth 12 -Compress
    $request.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
    return @{Client=$client;Request=$request;Task=$client.SendAsync($request)}
}
function Complete-Graph-Wait($pending){
    try {
        $response=$pending.Task.GetAwaiter().GetResult()
        $json=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
        $result=$json.result.structuredContent
        if(-not $response.IsSuccessStatusCode -or $result.observation.state -ne 'SUCCEEDED'){
            throw "Multi-profile Task Graph wait failed: $($json|ConvertTo-Json -Depth 12 -Compress)"
        }
    } finally {
        $pending.Request.Dispose()
        $pending.Client.Dispose()
    }
}
$a=New-Profile a 8766;$b=New-Profile b 8767;$pa=$null;$pb=$null
try{
    $pa=Start-Process -FilePath (Join-Path $ReleaseDir 'runtime-app.exe') `
        -ArgumentList '--config runtime.yml --no-cli' -WorkingDirectory $a.Dir -WindowStyle Hidden -PassThru
    $pb=Start-Process -FilePath (Join-Path $ReleaseDir 'runtime-app.exe') `
        -ArgumentList '--config runtime.yml --no-cli' -WorkingDirectory $b.Dir -WindowStyle Hidden -PassThru
    $limit=[DateTime]::UtcNow.AddSeconds(20)
    while(((-not (Health $a)) -or (-not (Health $b))) -and [DateTime]::UtcNow -lt $limit){
        Start-Sleep -Milliseconds 200
    }
    if((-not (Health $a)) -or (-not (Health $b))){
        throw 'Two Runtime profiles did not report matching authenticated health and Task Graph telemetry'
    }

    $waitA=Start-Graph-Wait $a
    $waitB=Start-Graph-Wait $b
    $telemetryLimit=[DateTime]::UtcNow.AddSeconds(5)
    do{
        Start-Sleep -Milliseconds 100
        $healthA=Health-Snapshot $a
        $healthB=Health-Snapshot $b
        $waitingVisible=$healthA.taskGraph.timedWaits -eq 1 -and
            $healthB.taskGraph.timedWaits -eq 1 -and
            $healthA.taskGraph.durable.states.WAITING -eq 1 -and
            $healthB.taskGraph.durable.states.WAITING -eq 1
    }while(-not $waitingVisible -and [DateTime]::UtcNow -lt $telemetryLimit)
    if(-not $waitingVisible){throw 'Both profile-local Task Graph waits were not visible in telemetry'}
    Complete-Graph-Wait $waitA
    Complete-Graph-Wait $waitB
    $healthA=Health-Snapshot $a
    $healthB=Health-Snapshot $b
    if($healthA.taskGraph.durable.totalExecutions -ne 1 -or
        $healthB.taskGraph.durable.totalExecutions -ne 1 -or
        $healthA.taskGraph.durable.states.SUCCEEDED -ne 1 -or
        $healthB.taskGraph.durable.states.SUCCEEDED -ne 1){
        throw 'Task Graph telemetry crossed profile boundaries or did not retain terminal state'
    }

    Stop-Process -Id $pa.Id -Force
    $pa.WaitForExit()
    Start-Sleep -Milliseconds 300
    $survivor=Health-Snapshot $b
    if(-not (Health $b) -or $survivor.taskGraph.durable.totalExecutions -ne 1){
        throw 'Stopping profile A affected profile B or its Task Graph telemetry'
    }
    Write-Output 'Runtime multi-profile test passed: isolated authenticated identity and Task Graph telemetry; stop A left B healthy.'
}finally{
    foreach($p in @($pa,$pb)){
        if($p -and (-not $p.HasExited)){Stop-Process -Id $p.Id -Force}
    }
}
