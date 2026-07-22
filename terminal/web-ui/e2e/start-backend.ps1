$ErrorActionPreference = 'Stop'
$sourceWebRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repository = (Resolve-Path (Join-Path $sourceWebRoot '..\..')).Path
$fixtureSource = Join-Path $sourceWebRoot 'e2e\fixtures\pcl2'
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

$release = if ($env:MCAC_E2E_RELEASE_DIR) {
    (Resolve-Path -LiteralPath $env:MCAC_E2E_RELEASE_DIR).Path
} else { $null }
$terminal = if ($release) { Join-Path $release 'mcac.exe' } else {
    Join-Path $repository 'terminal\terminal-app\build\install\mcac\bin\mcac.bat'
}
$servedWebRoot = if ($release) { Join-Path $release 'web' } else { Join-Path $sourceWebRoot 'dist' }
if (-not (Test-Path -LiteralPath $terminal -PathType Leaf)) { throw "Terminal entrypoint is missing: $terminal" }
if (-not (Test-Path -LiteralPath $servedWebRoot -PathType Container)) { throw "Web root is missing: $servedWebRoot" }
& $terminal web --no-browser --port 32145 --state-file $state --web-root $servedWebRoot --root $fixture
exit $LASTEXITCODE
