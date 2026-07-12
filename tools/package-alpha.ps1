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

$archive = Join-Path $delivery 'source-archive\minecraft-ai-companion-0.1.0-alpha.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
$insideWorkTree = git -C $repo rev-parse --is-inside-work-tree 2>$null
if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne 'true') {
    throw 'Source archive requires an initialized Git worktree.'
}
git -C $repo archive --format=zip --output=$archive HEAD
if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }

$hashes = Get-ChildItem -LiteralPath $delivery -Recurse -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        $relative = $_.FullName.Substring($delivery.Length).TrimStart([char[]]'\/').Replace('\', '/')
        '{0}  {1}' -f $hash.Hash.ToLowerInvariant(), $relative
    }
[IO.File]::WriteAllLines((Join-Path $delivery 'SHA256SUMS.txt'), $hashes, [Text.UTF8Encoding]::new($false))
Write-Output "Alpha delivery assembled at $delivery"
