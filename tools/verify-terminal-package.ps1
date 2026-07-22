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
    'KNOWN_LIMITATIONS.md',
    'docs\POST_PRODUCTIZATION_P0.md',
    'release-manifest.json',
    'sbom.spdx.json',
    'SHA256SUMS.txt'
)

foreach ($item in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $release $item))) {
        throw "Missing release item: $item"
    }
}

$slf4jProviders = @(Get-ChildItem -LiteralPath (Join-Path $release 'app') -File | Where-Object {
    $_.Name -match '^(slf4j-(simple|nop|jdk14|reload4j)|logback-classic)-.*\.jar$'
})
if ($slf4jProviders.Count -ne 1 -or $slf4jProviders[0].Name -notmatch '^slf4j-simple-') {
    throw "Release must contain exactly one SLF4J provider (slf4j-simple): $($slf4jProviders.Name -join ', ')"
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

$manifest = Get-Content -LiteralPath (Join-Path $release 'release-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.product -ne 'Minecraft AI Companion') {
    throw 'Release manifest identity/schema is invalid'
}
if ($manifest.sourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'Release manifest source commit is invalid' }
$manifestPaths = @{}
foreach ($entry in $manifest.files) {
    if ($entry.path -match '(^|/)\.\.(/|$)' -or [IO.Path]::IsPathRooted([string]$entry.path)) {
        throw "Unsafe release manifest path: $($entry.path)"
    }
    $target = Join-Path $release ([string]$entry.path -replace '/', '\')
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Manifest target is missing: $($entry.path)" }
    if ((Get-Item -LiteralPath $target).Length -ne [long]$entry.size) { throw "Manifest size mismatch: $($entry.path)" }
    if ((Get-Sha256 $target) -ne [string]$entry.sha256) { throw "Manifest hash mismatch: $($entry.path)" }
    $manifestPaths[[string]$entry.path] = $true
}
$payloadPaths = Get-ChildItem -Recurse -File -LiteralPath $release | ForEach-Object {
    $relative = $_.FullName.Substring($release.Length).TrimStart('\').Replace('\', '/')
    if ($relative -notin @('SHA256SUMS.txt', 'release-manifest.json', 'sbom.spdx.json')) { $relative }
}
foreach ($path in $payloadPaths) { if (-not $manifestPaths.ContainsKey($path)) { throw "Manifest omitted payload: $path" } }
if ($manifestPaths.Count -ne @($payloadPaths).Count) { throw 'Release manifest contains duplicate or extra paths' }

$sbom = Get-Content -LiteralPath (Join-Path $release 'sbom.spdx.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($sbom.spdxVersion -ne 'SPDX-2.3' -or $sbom.dataLicense -ne 'CC0-1.0') { throw 'SPDX SBOM header is invalid' }
if (@($sbom.packages).Count -lt 1) { throw 'SPDX SBOM contains no packages' }
foreach ($package in $sbom.packages) {
    $target = Join-Path $release ([string]$package.packageFileName -replace '/', '\')
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "SBOM package target missing: $($package.packageFileName)" }
    $checksum = @($package.checksums | Where-Object algorithm -eq 'SHA256') | Select-Object -First 1
    if (-not $checksum -or (Get-Sha256 $target) -ne [string]$checksum.checksumValue) {
        throw "SBOM package checksum mismatch: $($package.packageFileName)"
    }
}

$zipPath = Join-Path (Split-Path $release -Parent) 'mcac-release.zip'
if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) { throw 'Release ZIP is missing' }
$zipShaPath = "$zipPath.sha256"
if (-not (Test-Path -LiteralPath $zipShaPath -PathType Leaf)) { throw 'Release ZIP SHA-256 sidecar is missing' }
$zipShaLine = (Get-Content -LiteralPath $zipShaPath -Raw -Encoding UTF8).Trim()
if ($zipShaLine -notmatch '^([0-9a-f]{64})  mcac-release\.zip$' -or (Get-Sha256 $zipPath) -ne $Matches[1]) {
    throw 'Release ZIP SHA-256 sidecar does not match the package'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($zip.Entries | ForEach-Object FullName)
    foreach ($name in @('mcac.exe', 'mcac-cli.exe', 'mcac.cmd', 'mcac.ps1', $starterName, 'web/index.html',
            'release-manifest.json', 'sbom.spdx.json', 'SHA256SUMS.txt')) {
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
