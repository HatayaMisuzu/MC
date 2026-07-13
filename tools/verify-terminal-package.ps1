param([Parameter(Mandatory = $true)][string]$ReleaseDir)

$ErrorActionPreference = 'Stop'
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path
$starterName = [string]([char]0x542F) + [char]0x52A8 + [char]0x7EC8 + [char]0x7AEF + '.cmd'
$required = @(
    'mcac.exe',
    'mcac-cli.exe',
    'mcac.cmd',
    'mcac.ps1',
    $starterName,
    'app',
    'runtime',
    'web',
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

function Get-Sha256([string]$Path) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $stream = [IO.File]::OpenRead($Path)
        try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose() }
    }
    finally { $algorithm.Dispose() }
}

$sumFile = Join-Path $release 'SHA256SUMS.txt'
foreach ($line in Get-Content -LiteralPath $sumFile -Encoding UTF8) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Malformed SHA256SUMS line: $line" }
    $target = Join-Path $release ($Matches[2] -replace '/', '\')
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Hash target is missing: $($Matches[2])" }
    $actual = Get-Sha256 $target
    if ($actual -ne $Matches[1]) { throw "SHA-256 mismatch: $($Matches[2])" }
}

$zipPath = Join-Path (Split-Path $release -Parent) 'mcac-release.zip'
if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) { throw 'Release ZIP is missing' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($zip.Entries | ForEach-Object FullName)
    foreach ($name in @('mcac.exe', 'mcac-cli.exe', 'mcac.cmd', 'mcac.ps1', $starterName, 'web/index.html')) {
        if ($names -notcontains $name) { throw "ZIP first layer is missing $name" }
    }
    if ($names | Where-Object { $_ -match '^mcac-release/' }) {
        throw 'ZIP contains an unexpected wrapper directory'
    }
}
finally { $zip.Dispose() }

Push-Location $release
try {
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
