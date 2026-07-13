[CmdletBinding()]param([Parameter(Mandatory=$true)][string]$ReleaseDir)
$ErrorActionPreference='Stop';$testHome=Join-Path (Split-Path $ReleaseDir -Parent) 'runtime-multi-profile-test'
if(Test-Path $testHome){Remove-Item -LiteralPath $testHome -Recurse -Force};New-Item -ItemType Directory -Path $testHome|Out-Null
function New-Profile([string]$id,[int]$port){$dir=Join-Path $testHome $id;New-Item -ItemType Directory -Path $dir|Out-Null;$bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();try{$rng.GetBytes($bytes)}finally{$rng.Dispose()};$token=[Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=');Set-Content -LiteralPath (Join-Path $dir 'pairing.token') -Value $token -Encoding ascii;@"
server:
  bind: 127.0.0.1
  port: $port
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
"@|Set-Content -LiteralPath (Join-Path $dir 'runtime.yml') -Encoding utf8;return $dir}
function Port([int]$p){$c=[Net.Sockets.TcpClient]::new();try{$c.Connect('127.0.0.1',$p);return $true}catch{return $false}finally{$c.Dispose()}}
$a=New-Profile a 8766;$b=New-Profile b 8767;$pa=$null;$pb=$null
try{$pa=Start-Process -FilePath (Join-Path $ReleaseDir 'runtime-app.exe') -ArgumentList '--config',(Join-Path $a 'runtime.yml'),'--no-cli' -WorkingDirectory $a -WindowStyle Hidden -PassThru;$pb=Start-Process -FilePath (Join-Path $ReleaseDir 'runtime-app.exe') -ArgumentList '--config',(Join-Path $b 'runtime.yml'),'--no-cli' -WorkingDirectory $b -WindowStyle Hidden -PassThru;$limit=[DateTime]::UtcNow.AddSeconds(20);while(((-not (Port 8766)) -or (-not (Port 8767))) -and [DateTime]::UtcNow -lt $limit){Start-Sleep -Milliseconds 200};if((-not (Port 8766)) -or (-not (Port 8767))){throw 'Two Runtime profiles did not become ready'};Stop-Process -Id $pa.Id -Force;$pa.WaitForExit();Start-Sleep -Milliseconds 300;if(-not (Port 8767)){throw 'Stopping profile A affected profile B'};Write-Output 'Runtime multi-profile test passed: 8766 and 8767 online; stop A left B online.'}finally{foreach($p in @($pa,$pb)){if($p -and (-not $p.HasExited)){Stop-Process -Id $p.Id -Force}}}
