[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$DeliveryRoot
)

$ErrorActionPreference = 'Stop'
if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
$repo = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if (-not $DeliveryRoot) {
    $DeliveryRoot = Join-Path (Split-Path -Parent $repo) 'minecraft-companion-alpha-0.1-delivery'
}
$delivery = [IO.Path]::GetFullPath($DeliveryRoot)
$repoFull = [IO.Path]::GetFullPath($repo)
if ($delivery.StartsWith($repoFull, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'DeliveryRoot must be outside the source repository.'
}
if ($delivery.Length -lt 10 -or [IO.Path]::GetPathRoot($delivery) -eq $delivery) {
    throw 'DeliveryRoot is not a safe removable directory.'
}
if (Test-Path -LiteralPath $delivery) {
    Remove-Item -LiteralPath $delivery -Recurse -Force
}

$directories = @(
    'jars\fabric-1.21.1',
    'jars\neoforge-1.21.1',
    'jars\forge-1.20.1',
    'runtime',
    'install',
    'test-results',
    'launch-logs',
    'compatibility',
    'traces',
    'source-archive'
)
New-Item -ItemType Directory -Force -Path $delivery | Out-Null
foreach ($dir in $directories) {
    New-Item -ItemType Directory -Force -Path (Join-Path $delivery $dir) | Out-Null
}

$artifactMap = [ordered]@{
    'minecraft\fabric-1.21.1\build\libs' = 'jars\fabric-1.21.1'
    'minecraft\neoforge-1.21.1\build\libs' = 'jars\neoforge-1.21.1'
    'minecraft\forge-1.20.1\build\libs' = 'jars\forge-1.20.1'
}
foreach ($entry in $artifactMap.GetEnumerator()) {
    $source = Join-Path $repo $entry.Key
    if (Test-Path -LiteralPath $source) {
        Get-ChildItem -LiteralPath $source -File -Filter *.jar |
            Where-Object { $_.Name -notmatch '(-sources|-dev|-shadow)\.jar$' } |
            Copy-Item -Destination (Join-Path $delivery $entry.Value) -Force
    }
}

$runtimeDist = Join-Path $repo 'runtime\runtime-app\build\distributions'
if (Test-Path -LiteralPath $runtimeDist) {
    Get-ChildItem -LiteralPath $runtimeDist -File | Copy-Item -Destination (Join-Path $delivery 'runtime') -Force
}

Get-ChildItem -LiteralPath (Join-Path $repo 'docs') -File -ErrorAction SilentlyContinue |
    Copy-Item -Destination (Join-Path $delivery 'install') -Force
Copy-Item -LiteralPath (Join-Path $repo 'README.md') -Destination (Join-Path $delivery 'install') -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath (Join-Path $repo 'KNOWN_LIMITATIONS.md') -Destination $delivery -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath (Join-Path $repo 'LICENSE') -Destination $delivery -Force
Copy-Item -LiteralPath (Join-Path $repo 'SOURCE_PROVENANCE.yml') -Destination (Join-Path $delivery 'compatibility') -Force
Copy-Item -LiteralPath (Join-Path $repo 'docs\COMPATIBILITY.md') -Destination (Join-Path $delivery 'compatibility') -Force

function Copy-DirectoryContents([string]$source, [string]$destination) {
    if (-not (Test-Path -LiteralPath $source)) { return }
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $destination -Recurse -Force
}

Copy-DirectoryContents (Join-Path $repo 'core\pure-core\build\test-results') (Join-Path $delivery 'test-results\core')
Copy-DirectoryContents (Join-Path $repo 'protocol\protocol-model\build\test-results') (Join-Path $delivery 'test-results\protocol')
Copy-DirectoryContents (Join-Path $repo 'runtime\runtime-app\build\test-results') (Join-Path $delivery 'test-results\runtime')
Copy-DirectoryContents (Join-Path $repo 'minecraft\fabric-1.21.1\build\test-results') (Join-Path $delivery 'test-results\fabric-1.21.1')
Copy-DirectoryContents (Join-Path $repo 'minecraft\neoforge-1.21.1\build\gametest\logs') (Join-Path $delivery 'test-results\neoforge-1.21.1')
Copy-DirectoryContents (Join-Path $repo 'minecraft\forge-1.20.1\build\gametest\logs') (Join-Path $delivery 'test-results\forge-1.20.1')

foreach ($target in @('fabric-1.21.1', 'neoforge-1.21.1', 'forge-1.20.1')) {
    Copy-DirectoryContents (Join-Path $repo "minecraft\$target\build\launch-test\server\logs") `
        (Join-Path $delivery "launch-logs\$target")
}
Copy-DirectoryContents (Join-Path $repo 'build\e2e-runtime') (Join-Path $delivery 'traces\runtime-fabric-e2e')
Copy-DirectoryContents (Join-Path $repo 'build\persistence-restart-evidence') (Join-Path $delivery 'traces\persistence-restart')
Copy-DirectoryContents (Join-Path $repo 'build\stability-evidence') (Join-Path $delivery 'traces\stability')
Copy-DirectoryContents (Join-Path $repo 'build\release-audit') (Join-Path $delivery 'traces\release-audit')
Copy-DirectoryContents (Join-Path $repo 'build\clean-install-evidence') (Join-Path $delivery 'compatibility\clean-install')

$reportSource = Join-Path $repo 'FINAL_REPORT.md'
if (-not (Test-Path -LiteralPath $reportSource)) { throw 'FINAL_REPORT.md is missing.' }
$reportPath = Join-Path $delivery 'FINAL_REPORT.md'
Copy-Item -LiteralPath $reportSource -Destination $reportPath -Force

$archive = Join-Path $delivery 'source-archive\minecraft-ai-companion-0.1.0-alpha.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
$insideWorkTree = git -C $repo rev-parse --is-inside-work-tree 2>$null
if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne 'true') {
    throw 'Source archive requires an initialized Git worktree.'
}
git -C $repo archive --format=zip --output=$archive HEAD
if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }

$artifactRows = Get-ChildItem -LiteralPath (Join-Path $delivery 'jars'), (Join-Path $delivery 'runtime'), (Join-Path $delivery 'source-archive') -Recurse -File |
    Sort-Object FullName | ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $relative = $_.FullName.Substring($delivery.Length).TrimStart([char[]]'\/').Replace('\', '/')
        "| ``$relative`` | ``$hash`` |"
    }
$hashSection = @('', '## 交付产物 SHA-256', '', '| 文件 | SHA-256 |', '|---|---|') + $artifactRows
[IO.File]::AppendAllText($reportPath,
    [Environment]::NewLine + ($hashSection -join [Environment]::NewLine) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))

$hashes = Get-ChildItem -LiteralPath $delivery -Recurse -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        $relative = $_.FullName.Substring($delivery.Length).TrimStart([char[]]'\/').Replace('\', '/')
        '{0}  {1}' -f $hash.Hash.ToLowerInvariant(), $relative
    }
[IO.File]::WriteAllLines((Join-Path $delivery 'SHA256SUMS.txt'), $hashes, [Text.UTF8Encoding]::new($false))
Write-Output "Alpha delivery assembled at $delivery"
