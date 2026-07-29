[CmdletBinding()]param([Parameter(Mandatory=$true)][string]$ReleaseDir)
$ErrorActionPreference='Stop';$testHome=Join-Path (Split-Path $ReleaseDir -Parent) 'runtime-multi-profile-test'
Add-Type -AssemblyName System.Net.Http
if(Test-Path $testHome){Remove-Item -LiteralPath $testHome -Recurse -Force}
New-Item -ItemType Directory -Path $testHome|Out-Null

function Write-OwnerOnlyAsciiFile([string]$Path,[string]$Value){
    $stream=$null
    try{$stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)}
    finally{if($stream){$stream.Dispose()}}
    try{
        $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
        if(-not $identity.User){throw 'Current Windows identity has no SID'}
        $sid=$identity.User;$acl=Get-Acl -LiteralPath $Path
        $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
        foreach($rule in @($acl.Access)){[void]$acl.RemoveAccessRuleSpecific($rule)}
        [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $sid,[Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow))
        Set-Acl -LiteralPath $Path -AclObject $acl
        $verified=Get-Acl -LiteralPath $Path
        if($verified.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw 'Token owner verification failed'}
        if(-not $verified.AreAccessRulesProtected){throw 'Token ACL still inherits permissions'}
        $unexpected=@($verified.Access|Where-Object{$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value})
        if($unexpected.Count){throw "Token ACL has $($unexpected.Count) unexpected rule(s)"}
        [IO.File]::WriteAllText($Path,$Value+[Environment]::NewLine,[Text.Encoding]::ASCII)
    }catch{Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue;throw}
}
function New-Profile([string]$id,[int]$port){
    $dir=Join-Path $testHome $id;New-Item -ItemType Directory -Path $dir|Out-Null
    $bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
    $token=[Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
    Write-OwnerOnlyAsciiFile (Join-Path $dir 'pairing.token') $token
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
"@|Set-Content -LiteralPath (Join-Path $dir 'runtime.yml') -Encoding utf8
    return @{Dir=$dir;Token=$token;Id=$id;Port=$port;Stdout=Join-Path $dir 'runtime.stdout.log';Stderr=Join-Path $dir 'runtime.stderr.log'}
}
function Health-Snapshot($p){Invoke-RestMethod -UseBasicParsing -TimeoutSec 2 -Headers @{Authorization="Bearer $($p.Token)"} -Uri "http://127.0.0.1:$($p.Port+10000)/health"}
function Health($p){try{$r=Health-Snapshot $p;return $r.profileId -eq $p.Id -and $r.instanceId -eq $p.Id -and $r.protocolVersion -eq 'mc-companion/1' -and $r.port -eq $p.Port -and $r.taskGraph.status -eq 'READY'}catch{return $false}}
function Tail([string]$p){if(Test-Path $p){(Get-Content $p -Tail 80 -ErrorAction SilentlyContinue)-join [Environment]::NewLine}else{'<missing>'}}
function Assert-Alive($proc,$p){if($proc -and $proc.HasExited){throw "Profile $($p.Id) exited $($proc.ExitCode)`nSTDOUT:`n$(Tail $p.Stdout)`nSTDERR:`n$(Tail $p.Stderr)`nRUNTIME:`n$(Tail (Join-Path $p.Dir 'runtime.log'))"}}
function Failure-Summary($a,$b,$pa,$pb,$reason){
    [ordered]@{reason=$reason;generatedAt=[DateTimeOffset]::UtcNow.ToString('o');profiles=@(
        [ordered]@{id=$a.Id;pid=if($pa){$pa.Id}else{$null};exited=if($pa){$pa.HasExited}else{$null};stdout=$a.Stdout;stderr=$a.Stderr},
        [ordered]@{id=$b.Id;pid=if($pb){$pb.Id}else{$null};exited=if($pb){$pb.HasExited}else{$null};stdout=$b.Stdout;stderr=$b.Stderr}
    )}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $testHome 'failure-summary.json') -Encoding utf8
}
function New-McpSession($p){
    $c=[Net.Http.HttpClient]::new()
    try{$q=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,"http://127.0.0.1:$($p.Port+10000)/mcp")
        $q.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$p.Token)
        $q.Headers.Add('X-MCAC-Companion-Id',"telemetry-$($p.Id)");$q.Headers.Add('X-MCAC-Brain-Session-Id',"telemetry-$($p.Id)")
        $body=@{jsonrpc='2.0';id="init-$($p.Id)";method='initialize';params=@{protocolVersion='2025-06-18';capabilities=@{};clientInfo=@{name='mcac-multi-profile-test';version='test'}}}|ConvertTo-Json -Depth 8 -Compress
        $q.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json');$r=$c.SendAsync($q).GetAwaiter().GetResult()
        try{$txt=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult();if(-not $r.IsSuccessStatusCode){throw "MCP initialize failed: $txt"};return [string]($r.Headers.GetValues('Mcp-Session-Id')|Select-Object -First 1)}finally{$r.Dispose();$q.Dispose()}
    }finally{$c.Dispose()}
}
function Start-Wait($p){
    $c=[Net.Http.HttpClient]::new();$q=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,"http://127.0.0.1:$($p.Port+10000)/mcp")
    $q.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$p.Token);$q.Headers.Add('MCP-Protocol-Version','2025-06-18');$q.Headers.Add('Mcp-Session-Id',(New-McpSession $p))
    $q.Headers.Add('X-MCAC-Companion-Id',"telemetry-$($p.Id)");$q.Headers.Add('X-MCAC-Brain-Session-Id',"telemetry-$($p.Id)")
    $body=@{jsonrpc='2.0';id="wait-$($p.Id)";method='tools/call';params=@{name='task_graph.execute';arguments=@{graph=@{version='mcac-task-graph/1';id="multi-profile-telemetry-$($p.Id)";permissions=@();root=@{id='wait';type='wait';durationMillis=3000}};provenance=@{source='LOCAL_MULTI_PROFILE_TELEMETRY_TEST';liveModel=$false}}}}|ConvertTo-Json -Depth 12 -Compress
    $q.Content=[Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json');return @{Client=$c;Request=$q;Task=$c.SendAsync($q)}
}
function Complete-Wait($x){try{$r=$x.Task.GetAwaiter().GetResult();$j=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json;if(-not $r.IsSuccessStatusCode -or $j.result.structuredContent.observation.state -ne 'SUCCEEDED'){throw "Task Graph wait failed: $($j|ConvertTo-Json -Depth 12 -Compress)"}}finally{$x.Request.Dispose();$x.Client.Dispose()}}

$a=New-Profile a 8766;$b=New-Profile b 8767;$pa=$null;$pb=$null
try{
    $pa=Start-Process (Join-Path $ReleaseDir 'runtime-app.exe') -ArgumentList '--config runtime.yml --no-cli' -WorkingDirectory $a.Dir -WindowStyle Hidden -RedirectStandardOutput $a.Stdout -RedirectStandardError $a.Stderr -PassThru
    $pb=Start-Process (Join-Path $ReleaseDir 'runtime-app.exe') -ArgumentList '--config runtime.yml --no-cli' -WorkingDirectory $b.Dir -WindowStyle Hidden -RedirectStandardOutput $b.Stdout -RedirectStandardError $b.Stderr -PassThru
    $limit=[DateTime]::UtcNow.AddSeconds(20)
    while(((-not(Health $a))-or(-not(Health $b))) -and [DateTime]::UtcNow -lt $limit){Assert-Alive $pa $a;Assert-Alive $pb $b;Start-Sleep -Milliseconds 200}
    Assert-Alive $pa $a;Assert-Alive $pb $b
    if((-not(Health $a))-or(-not(Health $b))){throw 'Two Runtime profiles did not report matching authenticated health and Task Graph telemetry'}
    $wa=Start-Wait $a;$wb=Start-Wait $b;$tl=[DateTime]::UtcNow.AddSeconds(5)
    do{Start-Sleep -Milliseconds 100;$ha=Health-Snapshot $a;$hb=Health-Snapshot $b;$visible=$ha.taskGraph.timedWaits -eq 1 -and $hb.taskGraph.timedWaits -eq 1 -and $ha.taskGraph.durable.states.WAITING -eq 1 -and $hb.taskGraph.durable.states.WAITING -eq 1}while(-not $visible -and [DateTime]::UtcNow -lt $tl)
    if(-not $visible){throw 'Both profile-local Task Graph waits were not visible in telemetry'}
    Complete-Wait $wa;Complete-Wait $wb;$ha=Health-Snapshot $a;$hb=Health-Snapshot $b
    if($ha.taskGraph.durable.totalExecutions -ne 1 -or $hb.taskGraph.durable.totalExecutions -ne 1 -or $ha.taskGraph.durable.states.SUCCEEDED -ne 1 -or $hb.taskGraph.durable.states.SUCCEEDED -ne 1){throw 'Task Graph telemetry crossed profile boundaries or did not retain terminal state'}
    Stop-Process -Id $pa.Id -Force;$pa.WaitForExit();Start-Sleep -Milliseconds 300;$survivor=Health-Snapshot $b
    if(-not(Health $b) -or $survivor.taskGraph.durable.totalExecutions -ne 1){throw 'Stopping profile A affected profile B or its Task Graph telemetry'}
    Write-Output 'Runtime multi-profile test passed: isolated authenticated identity and Task Graph telemetry; stop A left B healthy.'
}catch{Failure-Summary $a $b $pa $pb $_.Exception.Message;throw}
finally{foreach($p in @($pa,$pb)){if($p -and -not $p.HasExited){Stop-Process -Id $p.Id -Force}}}
