param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$check = Join-Path $PSScriptRoot 'documentation-check.ps1'
$fixture = 'tools/test-fixtures/documentation/contradictory-product-truth.json'
$powershell = (Get-Process -Id $PID).Path
& $powershell -NoProfile -ExecutionPolicy Bypass -File $check `
    -RepositoryRoot $RepositoryRoot -ProductTruthOverride $fixture *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Error 'Documentation check accepted the deliberately contradictory product truth fixture.'
    exit 1
}
Write-Host 'Documentation negative check passed: contradictory product truth was rejected.'
$global:LASTEXITCODE = 0
