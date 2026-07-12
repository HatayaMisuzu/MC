[CmdletBinding()]
param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'
if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$productionRoots = @(
    'core',
    'protocol',
    'runtime',
    'minecraft'
)

$patterns = [ordered]@{
    'teleportTo\s*\('               = 'teleportTo'
    '\.setPos\s*\('                 = 'setPos'
    '\.moveTo\s*\('                 = 'moveTo'
    '\.setBlock[A-Za-z]*\s*\('      = 'setBlock'
    '\.removeBlock\s*\('            = 'removeBlock'
    '(Inventory|Container).*\.setItem\s*\(' = 'direct inventory/container setItem'
    '\.performCommand[A-Za-z]*\s*\(' = 'performCommand'
    '"/(give|tp|teleport|setblock|fill|summon)\b' = 'forbidden command literal'
}

$whitelistPath = Join-Path $root 'tools\forbidden-api-whitelist.txt'
$whitelist = @()
if (Test-Path -LiteralPath $whitelistPath) {
    $whitelist = Get-Content -LiteralPath $whitelistPath -Encoding utf8 |
        Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } |
        ForEach-Object {
            $parts = $_ -split '\|', 3
            if ($parts.Count -ne 3 -or [string]::IsNullOrWhiteSpace($parts[2])) {
                throw "Invalid forbidden API whitelist entry: $_"
            }
            [pscustomobject]@{
                Path = $parts[0].Trim().Replace('/', '\')
                Token = $parts[1].Trim()
                Reason = $parts[2].Trim()
            }
        }
}

$allowedSegments = @(
    [IO.Path]::DirectorySeparatorChar + 'src' + [IO.Path]::DirectorySeparatorChar + 'test' + [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::DirectorySeparatorChar + 'gametest' + [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::DirectorySeparatorChar + 'test-fixtures' + [IO.Path]::DirectorySeparatorChar
)

$violations = [System.Collections.Generic.List[string]]::new()
foreach ($relativeRoot in $productionRoots) {
    $scanRoot = Join-Path $root $relativeRoot
    if (-not (Test-Path -LiteralPath $scanRoot)) { continue }

    Get-ChildItem -LiteralPath $scanRoot -Recurse -File | Where-Object {
        $_.Extension -in @('.java', '.kt') -and
        $_.FullName -notmatch '[\\/](build|\.gradle|run|runs)[\\/]'
    } | ForEach-Object {
        $path = $_.FullName
        if ($allowedSegments | Where-Object { $path.Contains($_) }) { return }
        $lineNumber = 0
        foreach ($line in [IO.File]::ReadLines($path)) {
            $lineNumber++
            foreach ($entry in $patterns.GetEnumerator()) {
                if ($line -match $entry.Key) {
                    $relative = $path.Substring($root.Length).TrimStart([char[]]'\/')
                    $allowed = $whitelist | Where-Object {
                        $relative -like $_.Path -and $entry.Value -eq $_.Token
                    }
                    if ($allowed) { continue }
                    $violations.Add("${relative}:${lineNumber}: $($entry.Value): $($line.Trim())")
                }
            }
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    throw "Forbidden player-path bypasses detected: $($violations.Count)"
}

Write-Output 'Forbidden API check passed: no production task/action bypass was detected.'
