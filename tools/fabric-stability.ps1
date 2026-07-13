[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$fabric = Join-Path $root 'minecraft\fabric-1.21.1'
$evidence = Join-Path $root 'build\stability-evidence'

if (Test-Path -LiteralPath $evidence) {
    Remove-Item -LiteralPath $evidence -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

$output = Join-Path $evidence 'fabric-two-minute.out.log'
$errors = Join-Path $evidence 'fabric-two-minute.err.log'
$process = Start-Process -FilePath (Join-Path $fabric 'gradlew.bat') `
    -ArgumentList 'runGameTest', '-PmccompanionStability=true', '--no-daemon' `
    -WorkingDirectory $fabric -Wait -PassThru -NoNewWindow `
    -RedirectStandardOutput $output -RedirectStandardError $errors

if ($process.ExitCode -ne 0) {
    throw "Fabric stability GameTest failed with exit code $($process.ExitCode). See $output and $errors"
}
$text = Get-Content -Raw -LiteralPath $output
if ($text -notmatch 'All [0-9]+ required tests passed') {
    throw 'Fabric stability GameTest did not report a passing real GameTest.'
}
if ((Get-Content -Raw -LiteralPath $errors) -match '(?i)(outofmemory|exception|error)') {
    throw 'Fabric stability GameTest emitted an error on stderr.'
}
Write-Output 'Fabric two-minute stability GameTest passed: body retained, follow/stop completed, fake connection retained zero packets.'
