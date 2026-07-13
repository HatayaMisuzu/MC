[CmdletBinding()]
param([string]$RepositoryRoot)

$ErrorActionPreference = 'Stop'
if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$matches = Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object {
        $_.FullName -notmatch '[\\/](\.git|\.gradle|build|run|runs|mcac-local)[\\/]' -and
        $_.Extension -notin @('.jar', '.zip', '.class', '.png', '.jpg', '.jpeg')
    } |
    Select-String -Pattern 'sk-[A-Za-z0-9_-]{16,}' -ErrorAction SilentlyContinue

if (@($matches).Count -gt 0) {
    throw "Potential API key material detected in $(@($matches).Count) source location(s)."
}
Write-Output 'Secret check passed: no key-shaped value is present in the source workspace.'
