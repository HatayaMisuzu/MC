[CmdletBinding()]
param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'
if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$violations = [System.Collections.Generic.List[string]]::new()
$files = Get-ChildItem -LiteralPath $root -Recurse -File |
    Where-Object {
        $_.Extension -in @('.java', '.kt', '.gradle', '.kts', '.toml', '.json') -and
        $_.FullName -notmatch '[\\/]build[\\/]' -and
        $_.FullName -notmatch '[\\/]\.gradle[\\/]' -and
        $_.FullName -notmatch '[\\/]docs[\\/]' -and
        $_.FullName -notmatch '[\\/]tools[\\/]' -and
        $_.Name -ne 'SOURCE_PROVENANCE.yml'
    }

foreach ($file in $files) {
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadLines($file.FullName)) {
        $lineNumber++
        if ($line -match '(?i)com\.dwinovo\.numen|numen-api|numen-mcp|minecraft-numen|cabaletta.*baritone|baritone-api') {
            $relative = $file.FullName.Substring($root.Length).TrimStart([char[]]'\/')
            $violations.Add("${relative}:${lineNumber}: $($line.Trim())")
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    throw "Forbidden production dependencies detected: $($violations.Count)"
}

Write-Output 'Independence check passed: no Numen or Baritone production dependency was detected.'
