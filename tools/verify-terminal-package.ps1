param([Parameter(Mandatory = $true)][string]$ReleaseDir)

$ErrorActionPreference = 'Stop'
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path
$starterName = [string]([char]0x542F) + [char]0x52A8 + [char]0x7EC8 + [char]0x7AEF + '.cmd'
$required = @(
    'mcac.exe',
    'mcac.cmd',
    'mcac.ps1',
    $starterName,
    'app',
    'runtime',
    'artifacts\fabric-1.21.1',
    'artifacts\neoforge-1.21.1',
    'artifacts\forge-1.20.1',
    'legal',
    'README.txt',
    'SHA256SUMS.txt'
)

foreach ($item in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $release $item))) {
        throw "Missing release item: $item"
    }
}

Push-Location $release
try {
    & (Join-Path $release 'mcac.exe') --version
    if ($LASTEXITCODE -ne 0) { throw 'mcac.exe --version failed' }

    & (Join-Path $release 'mcac.cmd') --version
    if ($LASTEXITCODE -ne 0) { throw 'mcac.cmd --version failed' }

    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $release 'mcac.ps1') --version
    if ($LASTEXITCODE -ne 0) { throw 'mcac.ps1 --version failed' }

    $starter = Join-Path $release $starterName
    cmd /d /c "`"$starter`" --version"
    if ($LASTEXITCODE -ne 0) { throw 'release starter --version failed' }
}
finally {
    Pop-Location
}

$forbidden = Get-ChildItem -Recurse -File -LiteralPath $release | Where-Object {
    $_.Name -match '(?i)(account|launcher_accounts|\.token$|\.db$|\.log$)'
}
if ($forbidden) {
    throw "Forbidden release files: $($forbidden.FullName -join ', ')"
}

Write-Output 'Terminal release package verification passed.'
