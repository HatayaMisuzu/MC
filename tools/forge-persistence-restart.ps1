[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$forge = Join-Path $root 'minecraft\forge-1.20.1'
$runDirectory = Join-Path $forge 'build\launch-test\server'
$evidence = Join-Path $root 'build\forge-persistence-restart-evidence'
if (-not $runDirectory.StartsWith($forge, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to clean a run directory outside the Forge workspace.'
}
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

Push-Location $forge
try {
    $ErrorActionPreference = 'Continue'
    $seed = & '.\gradlew.bat' runServer -PmccompanionPersistenceProbe=seed --no-daemon 2>&1
    $seedExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $seed | Set-Content -LiteralPath (Join-Path $evidence 'seed-and-stop.log') -Encoding UTF8
    if ($seedExit -ne 0 -or ($seed -join "`n") -notmatch 'MCAC_FORGE_PERSISTENCE_SEED_READY') {
        throw 'Forge persistence seed server did not reach in-flight navigation and stop normally.'
    }

    $ErrorActionPreference = 'Continue'
    $verify = & '.\gradlew.bat' runServer -PmccompanionPersistenceProbe=verify --no-daemon 2>&1
    $verifyExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $verify | Set-Content -LiteralPath (Join-Path $evidence 'restart-and-verify.log') -Encoding UTF8
    if ($verifyExit -ne 0 -or ($verify -join "`n") -notmatch 'MCAC_FORGE_PERSISTENCE_RESTART_VERIFIED') {
        throw 'Forge restart did not recover UUID, inventory, body and safe navigation state.'
    }

    Write-Output 'Forge persistence restart passed: in-flight navigation recovered PAUSED with UUID and inventory intact.'
} finally {
    Pop-Location
}
