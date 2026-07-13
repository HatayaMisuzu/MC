$ErrorActionPreference = 'Stop'
$webRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repository = (Resolve-Path (Join-Path $webRoot '..\..')).Path
$fixtureSource = Join-Path $webRoot 'e2e\fixtures\pcl2'
$fixture = Join-Path $repository 'build\playwright-fixture'
$state = Join-Path $repository 'build\playwright-server.json'

Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $state -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $fixtureSource -Destination $fixture -Recurse -Force

Set-Location -LiteralPath $repository
if ($env:MCAC_E2E_PREBUILT -ne '1') {
    & (Join-Path $repository 'gradlew.bat') webBuild ':terminal:terminal-app:installDist' ':runtime:runtime-app:installDist' 'build-fabric-1.21.1'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$terminal = Join-Path $repository 'terminal\terminal-app\build\install\mcac\bin\mcac.bat'
& $terminal web --no-browser --port 32145 --state-file $state --web-root (Join-Path $webRoot 'dist') --root $fixture
exit $LASTEXITCODE
