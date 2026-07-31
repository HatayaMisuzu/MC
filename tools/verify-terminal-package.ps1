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
$expectedJarPaths = @($payloadPaths | Where-Object { $_ -like '*.jar' } | Sort-Object)
$actualJarPaths = @($sbom.packages | Where-Object { $_.packageFileName -like '*.jar' } |
    ForEach-Object { [string]$_.packageFileName } | Sort-Object)
if (($expectedJarPaths -join "`n") -ne ($actualJarPaths -join "`n")) {
    throw 'SPDX SBOM package targets do not exactly match packaged JARs'
}
if ([string]$sbom.documentNamespace -ne "https://github.com/HatayaMisuzu/MC/spdx/$($manifest.sourceCommit)") {
    throw 'SPDX SBOM namespace is not bound to the release source SHA'
}
$dependencyVersions = @{
    'Java-WebSocket-1.6.0.jar' = '1.6.0'
    'jackson-annotations-2.18.3.jar' = '2.18.3'
    'jackson-core-2.18.3.jar' = '2.18.3'
    'jackson-databind-2.18.3.jar' = '2.18.3'
    'jackson-dataformat-yaml-2.18.3.jar' = '2.18.3'
    'jackson-datatype-jsr310-2.18.3.jar' = '2.18.3'
    'jsoup-1.18.3.jar' = '1.18.3'
    'picocli-4.7.7.jar' = '4.7.7'
    'slf4j-api-2.0.17.jar' = '2.0.17'
    'slf4j-simple-2.0.17.jar' = '2.0.17'
    'snakeyaml-2.3.jar' = '2.3'
    'sqlite-jdbc-3.49.1.0.jar' = '3.49.1.0'
}
$npmVersions = @{
    'lucide-react' = '1.24.0'
    'react' = '19.2.7'
    'react-dom' = '19.2.7'
    'scheduler' = '0.27.0'
}
$observedNpm = @{}
foreach ($package in $sbom.packages) {
    if ([string]::IsNullOrWhiteSpace([string]$package.versionInfo) -or $package.versionInfo -eq 'NOASSERTION') {
        throw "SBOM package has no real version: $($package.name)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$package.licenseDeclared) -or $package.licenseDeclared -eq 'NOASSERTION') {
        throw "SBOM package has no declared license: $($package.name)"
    }
    $purl = @($package.externalRefs | Where-Object {
        $_.referenceCategory -eq 'PACKAGE-MANAGER' -and $_.referenceType -eq 'purl'
    }) | Select-Object -First 1
    if (-not $purl -or [string]::IsNullOrWhiteSpace([string]$purl.referenceLocator)) {
        throw "SBOM package has no package URL: $($package.name)"
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$package.packageFileName)) {
        $target = Join-Path $release ([string]$package.packageFileName -replace '/', '\')
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "SBOM package target missing: $($package.packageFileName)" }
        $checksum = @($package.checksums | Where-Object algorithm -eq 'SHA256') | Select-Object -First 1
        if (-not $checksum -or (Get-Sha256 $target) -ne [string]$checksum.checksumValue) {
            throw "SBOM package checksum mismatch: $($package.packageFileName)"
        }
    }
    else {
        if ([string]$purl.referenceLocator -notlike 'pkg:npm/*') {
            throw "SBOM package without a file is not an npm bundle component: $($package.name)"
        }
        $checksum = @($package.checksums | Where-Object algorithm -eq 'SHA512') | Select-Object -First 1
        if (-not $checksum -or [string]$checksum.checksumValue -notmatch '^[0-9a-f]{128}$') {
            throw "Bundled npm package has no lockfile SHA-512: $($package.name)"
        }
        if (-not $npmVersions.ContainsKey([string]$package.name) -or
                [string]$package.versionInfo -ne [string]$npmVersions[[string]$package.name]) {
            throw "Bundled npm package identity mismatch: $($package.name)@$($package.versionInfo)"
        }
        $observedNpm[[string]$package.name] = $true
        continue
    }
    $jarName = Split-Path -Leaf ([string]$package.packageFileName)
    if ($dependencyVersions.ContainsKey($jarName) -and
            [string]$package.versionInfo -ne [string]$dependencyVersions[$jarName]) {
        throw "SBOM dependency version mismatch for ${jarName}: $($package.versionInfo)"
    }
    if (-not $dependencyVersions.ContainsKey($jarName) -and $jarName -ne 'jrt-fs.jar' -and
            [string]$package.versionInfo -ne [string]$manifest.version) {
        throw "SBOM MCAC component version mismatch for ${jarName}: $($package.versionInfo)"
    }
    if ($dependencyVersions.ContainsKey($jarName) -and
            [string]$package.versionInfo -eq [string]$manifest.version) {
        throw "SBOM dependency was overwritten with the product version: $jarName"
    }
}
if ((($observedNpm.Keys | Sort-Object) -join "`n") -ne
        (($npmVersions.Keys | Sort-Object) -join "`n")) {
    throw 'SPDX SBOM bundled npm package set is incomplete or contains extras'
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

    # The release starter now launches `mcac.exe web --open-browser` by design,
    # so it is not a CLI that answers --version; the terminal-entrypoint tests
    # verify its web launch behavior and the MCAC_NO_BROWSER safety veto instead.
    if (-not (Test-Path -LiteralPath (Join-Path $release $starterName) -PathType Leaf)) {
        throw 'release starter is missing'
    }
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
