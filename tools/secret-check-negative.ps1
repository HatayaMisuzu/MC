param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$check = Join-Path $PSScriptRoot 'secret-check.ps1'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $check `
    -RepositoryRoot $RepositoryRoot -IncludeTestFixture *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Error 'Secret check accepted its deliberately secret-shaped fixture.'
    exit 1
}
Write-Host 'Secret negative check passed: the exact allowlisted fixture was rejected when enabled.'
$global:LASTEXITCODE = 0
