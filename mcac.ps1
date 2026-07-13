$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$launcher = if ($args.Count -eq 0) { 'mcac.exe' } else { 'mcac-cli.exe' }
$candidates = @(
    (Join-Path $root $launcher),
    (Join-Path $root "build\distributions\mcac-release\$launcher"),
    (Join-Path $root 'build\distributions\mcac-windows-x64\mcac-windows-x64.exe'),
    (Join-Path $root "mcac-local\$launcher"),
    (Join-Path $root 'terminal\terminal-app\build\install\mcac\bin\mcac.bat')
)
$mcac = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $mcac) {
    & (Join-Path $root 'gradlew.bat') 'stageTerminalAtProjectRoot'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $mcac = Join-Path $root "mcac-local\$launcher"
}
& $mcac @args
exit $LASTEXITCODE
