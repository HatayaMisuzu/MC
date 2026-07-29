$ErrorActionPreference = 'Stop'
$sourceWebRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repository = (Resolve-Path (Join-Path $sourceWebRoot '..\..')).Path
$fixtureSource = Join-Path $sourceWebRoot 'e2e\fixtures\pcl2'
# Sensitive terminal state deliberately uses owner-only ACLs. A repository checkout can inherit
# broad workspace ACLs on Windows, so keep the disposable runtime fixture in the user's temp area.
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) 'mcac-playwright-fixture'
$state = Join-Path ([System.IO.Path]::GetTempPath()) 'mcac-playwright-server.json'
$compatV1 = Join-Path $repository 'build\playwright-compat-v1.mcac-compat'
$compatV2 = Join-Path $repository 'build\playwright-compat-v2.mcac-compat'

Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $state -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $fixtureSource -Destination $fixture -Recurse -Force
$env:LOCALAPPDATA = Join-Path $fixture 'local-app-data'
$env:APPDATA = Join-Path $fixture 'roaming-app-data'
New-Item -ItemType Directory -Path $env:LOCALAPPDATA -Force | Out-Null
New-Item -ItemType Directory -Path $env:APPDATA -Force | Out-Null

function Get-Sha256Hex {
    param([string]$Path)
    $stream = [System.IO.File]::OpenRead($Path)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '')
    } finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

function New-CompatibilityFixture {
    param([string]$Output, [string]$Version, [string]$Value)
    $stage = Join-Path $repository ('build\playwright-compat-stage-' + $Version)
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path (Join-Path $stage 'capabilities') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stage 'evidence') -Force | Out-Null
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $manifest = @"
schemaVersion: mcac-compat/1
pack:
  id: fixture.ui
  version: $Version
  type: loader
  displayName: Browser lifecycle fixture
  authorType: external-agent
  createdAt: 2026-07-27T00:00:00Z
target:
  minecraft:
    exact: 1.21.1
  loader:
    type: fabric
    versionRange: ""
runtime:
  minimumHostVersion: 1
  nativeCode: false
  hotReloadable: true
  restartRequired: false
permissions:
  declared:
    - REGISTRY_READ
    - WORLD_OBSERVE
  forbidden:
    - SHELL
    - ARBITRARY_FILE_ACCESS
    - DIRECT_WORLD_EDIT
    - CREDENTIAL_READ
    - PROCESS_EXEC
    - NETWORK_UNBOUNDED
dependencies: []
conflicts: []
replaces: []
extends: []
patches: []
precedence: 0
limitations:
  - Browser fixture only; not arbitrary Mod compatibility evidence.
"@
    $tools = @"
{
  "schemaVersion": "mcac-capabilities/1",
  "capabilities": [{
    "id": "fixture.ui.observe",
    "kind": "tool",
    "risk": "LOW",
    "enabled": true,
    "contract": {"value": "$Value"}
  }]
}
"@
    [System.IO.File]::WriteAllText((Join-Path $stage 'manifest.yaml'), $manifest, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $stage 'capabilities\tools.json'), $tools, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $stage 'capabilities\observations.yaml'), "schemaVersion: mcac-observations/1`nobservations: []`n", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $stage 'capabilities\actions.yaml'), "schemaVersion: mcac-actions/1`nactions: []`n", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $stage 'capabilities\safety.yaml'), "schemaVersion: mcac-safety/1`nrules: []`n", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $stage 'evidence\limitations.json'), '{"schemaVersion":"mcac-limitations/1","limitations":[]}', $utf8)
    $stagePrefix = $stage.TrimEnd('\') + '\'
    $sums = Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($stagePrefix.Length).Replace('\', '/')
        "$(Get-Sha256Hex -Path $_.FullName)  $relative"
    }
    [System.IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), (($sums -join "`n") + "`n").ToLowerInvariant(), $utf8)
    Remove-Item -LiteralPath $Output -Force -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outputStream = [System.IO.File]::Open($Output, [System.IO.FileMode]::CreateNew)
    try {
        $zip = [System.IO.Compression.ZipArchive]::new(
            $outputStream, [System.IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
                $relative = $_.FullName.Substring($stagePrefix.Length).Replace('\', '/')
                $entry = $zip.CreateEntry($relative, [System.IO.Compression.CompressionLevel]::Optimal)
                $entryStream = $entry.Open()
                try {
                    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
                    $entryStream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $entryStream.Dispose()
                }
            }
        } finally {
            $zip.Dispose()
        }
    } finally {
        $outputStream.Dispose()
    }
}

New-CompatibilityFixture -Output $compatV1 -Version '1.0.0' -Value 'v1'
New-CompatibilityFixture -Output $compatV2 -Version '2.0.0' -Value 'v2'

Set-Location -LiteralPath $repository
if ($env:MCAC_E2E_PREBUILT -ne '1') {
    & (Join-Path $repository 'gradlew.bat') webBuild ':terminal:terminal-app:installDist' ':runtime:runtime-app:installDist' 'build-fabric-1.21.1'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$release = if ($env:MCAC_E2E_RELEASE_DIR) {
    (Resolve-Path -LiteralPath $env:MCAC_E2E_RELEASE_DIR).Path
} else { $null }
$terminal = if ($release) { Join-Path $release 'mcac.exe' } else {
    Join-Path $repository 'terminal\terminal-app\build\install\mcac\bin\mcac.bat'
}
$servedWebRoot = if ($release) { Join-Path $release 'web' } else { Join-Path $sourceWebRoot 'dist' }
if (-not (Test-Path -LiteralPath $terminal -PathType Leaf)) { throw "Terminal entrypoint is missing: $terminal" }
if (-not (Test-Path -LiteralPath $servedWebRoot -PathType Container)) { throw "Web root is missing: $servedWebRoot" }
& $terminal web --no-browser --port 32145 --state-file $state --web-root $servedWebRoot --root $fixture
exit $LASTEXITCODE
