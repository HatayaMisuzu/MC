param([Parameter(Mandatory=$true)][string]$ReleaseDir)
$ErrorActionPreference='Stop'
$required=@('mcac.exe','app','runtime','artifacts\fabric-1.21.1','artifacts\neoforge-1.21.1','artifacts\forge-1.20.1','legal','README.txt','SHA256SUMS.txt')
foreach($item in $required){if(-not(Test-Path -LiteralPath(Join-Path $ReleaseDir $item))){throw "Missing release item: $item"}}
if(-not(Get-ChildItem -LiteralPath $ReleaseDir -Filter '*.cmd' -File)){throw 'Missing release starter command'}
& (Join-Path $ReleaseDir 'mcac.exe') --version
if($LASTEXITCODE-ne 0){throw 'mcac.exe failed'}
$forbidden=Get-ChildItem -Recurse -File -LiteralPath $ReleaseDir | Where-Object {$_.Name -match '(?i)(account|launcher_accounts|\.token$|\.db$|\.log$)'}
if($forbidden){throw "Forbidden release files: $($forbidden.FullName -join ', ')"}
Write-Output 'Terminal release package verification passed.'
