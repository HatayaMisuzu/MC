[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$offlineArguments = @()
if ($env:MCAC_TEST_OFFLINE -eq '1') { $offlineArguments += '--offline' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$fabric = Join-Path $root 'minecraft\fabric-1.21.1'
$runDirectory = Join-Path $fabric 'build\gametest'
$evidence = Join-Path $root 'build\persistence-restart-evidence'
$fixtureBoundary = Join-Path $root 'tools\persistence-tests.init.gradle'
if (-not $runDirectory.StartsWith($fabric, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to clean a run directory outside the Fabric workspace.'
}
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

Push-Location $fabric
try {
$ErrorActionPreference = 'Continue'
$seed = & '.\gradlew.bat' runGameTest -PmccompanionPersistenceSeed=true --init-script $fixtureBoundary --no-daemon --no-parallel @offlineArguments 2>&1
$seedExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$seed | Set-Content -LiteralPath (Join-Path $evidence 'seed-and-stop.log') -Encoding UTF8
if ($seedExit -ne 0 -or ($seed -join "`n") -notmatch 'All [0-9]+ required tests passed') {
    throw 'Persistence seed server did not pass and stop normally.'
}

$ErrorActionPreference = 'Continue'
$verify = & '.\gradlew.bat' runGameTest -PmccompanionPersistenceVerify=true --init-script $fixtureBoundary --no-daemon --no-parallel @offlineArguments 2>&1
$verifyExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$verify | Set-Content -LiteralPath (Join-Path $evidence 'restart-and-verify.log') -Encoding UTF8
if ($verifyExit -ne 0 -or ($verify -join "`n") -notmatch 'All [0-9]+ required tests passed') {
    throw 'Restart server did not recover the companion UUID, body and inventory.'
}

Write-Output 'Fabric persistence restart passed: seed/save/stop/restart/body+UUID+inventory recovery.'
} finally {
    Pop-Location
}
