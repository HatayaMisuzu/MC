param(
    [Parameter(Mandatory = $true)][string]$ReleaseZip,
    [Parameter(Mandatory = $true)][string]$WebUiDir,
    [Parameter(Mandatory = $true)][string]$WorkDir
)

$ErrorActionPreference = 'Stop'
$zip = (Resolve-Path -LiteralPath $ReleaseZip).Path
$zipSha = "$zip.sha256"
if (-not (Test-Path -LiteralPath $zipSha -PathType Leaf)) { throw 'Release ZIP SHA-256 sidecar is missing' }
$expected = ((Get-Content -LiteralPath $zipSha -Raw -Encoding UTF8).Trim() -split '\s+')[0]
$actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw 'Release ZIP SHA-256 sidecar mismatch' }
$web = (Resolve-Path -LiteralPath $WebUiDir).Path
$workspace = [IO.Path]::GetFullPath($WorkDir)
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $workspace.StartsWith([IO.Path]::GetFullPath((Join-Path $repository 'build')),
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Golden Path workspace must stay inside the repository build directory.'
}
if (Test-Path -LiteralPath $workspace) { Remove-Item -LiteralPath $workspace -Recurse -Force }
New-Item -ItemType Directory -Path $workspace | Out-Null
$release = Join-Path $workspace 'release'
Expand-Archive -LiteralPath $zip -DestinationPath $release
foreach ($required in @('mcac.exe', 'mcac-cli.exe', 'web', 'artifacts\fabric-1.21.1',
        'release-manifest.json', 'sbom.spdx.json', 'SHA256SUMS.txt', 'KNOWN_LIMITATIONS.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $release $required))) {
        throw "Extracted release is missing $required"
    }
}

Push-Location $web
try {
    $env:MCAC_E2E_PREBUILT = '1'
    $env:MCAC_E2E_RELEASE_DIR = $release
    & npm run e2e
    if ($LASTEXITCODE -ne 0) { throw "Release Golden Path failed with exit code $LASTEXITCODE" }
}
finally {
    Remove-Item Env:MCAC_E2E_RELEASE_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:MCAC_E2E_PREBUILT -ErrorAction SilentlyContinue
    Pop-Location
}

Write-Output "Release Golden Path passed from clean extraction: $release"
