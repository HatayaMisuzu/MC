$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$candidates = @(
    (Join-Path $root 'mcac.exe'),
    (Join-Path $root 'build\distributions\mcac-release\mcac.exe'),
    (Join-Path $root 'build\distributions\mcac-windows-x64\mcac-windows-x64.exe'),
    (Join-Path $root 'mcac-local\mcac.exe'),
    (Join-Path $root 'terminal\terminal-app\build\install\mcac\bin\mcac.bat')
)
$mcac = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $mcac) {
    & (Join-Path $root 'gradlew.bat') 'stageTerminalAtProjectRoot'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $mcac = Join-Path $root 'mcac-local\mcac.exe'
}
& $mcac @args
exit $LASTEXITCODE
