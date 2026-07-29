[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [switch]$IncludeTestFixture
)

$ErrorActionPreference = 'Stop'
if (-not $RepositoryRoot) { $RepositoryRoot = Split-Path -Parent $PSScriptRoot }
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$fixture = 'tools/test-fixtures/secrets/intentional-secret-shapes.txt'
$binaryExtensions = @('.jar', '.zip', '.class', '.png', '.jpg', '.jpeg', '.gif', '.ico', '.exe', '.dll')
$findings = [System.Collections.Generic.List[string]]::new()
$exactTestAllowlist = @{
    'runtime/runtime-app/src/test/java/com/mccompanion/runtime/search/SearchToolGatewayTest.java' =
        @(('api_' + 'key=sk-' + 'abcdefghijkl'))
    'terminal/terminal-app/src/test/java/com/mccompanion/terminal/SearchConfigurationServiceTest.java' =
        @(('api_' + 'key=must-' + 'not-be-stored'))
}

$patterns = [ordered]@{
    'provider-key' = '(?<![A-Za-z0-9_-])sk-(?:proj-)?[A-Za-z0-9_-]{16,}'
    'github-token' = '(?<![A-Za-z0-9_])(?:ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})'
    'google-api-key' = '(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{30,}'
    'aws-access-key' = '(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])'
    'slack-token' = '(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{10,}'
    'discord-token' = '(?<![A-Za-z0-9_-])(?:mfa\.[A-Za-z0-9_-]{30,}|[A-Za-z0-9_-]{24}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{20,})'
    'jwt' = '(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}'
    'private-key' = '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'
    'bearer' = '(?i)Authorization\s*:\s*Bearer\s+(?!<|REDACTED|EXAMPLE|PLACEHOLDER)[A-Za-z0-9._~+/-]{12,}={0,2}'
    'credential-assignment' = '(?i)(?:api[_-]?key|password|client[_-]?secret)\s*[:=]\s*["'']?(?!<|REDACTED|EXAMPLE|PLACEHOLDER|\$\{|%)[A-Za-z0-9._~+/-]{12,}'
}

function Test-Text([string]$origin, [string]$text) {
    if ($exactTestAllowlist.ContainsKey($origin)) {
        foreach ($literal in $exactTestAllowlist[$origin]) {
            $text = $text.Replace($literal, '<EXACT_TEST_FIXTURE>')
        }
    }
    foreach ($entry in $patterns.GetEnumerator()) {
        if ([regex]::IsMatch($text, $entry.Value)) {
            $findings.Add("$origin [$($entry.Key)]")
        }
    }
}

$paths = @(& git -C $root -c core.quotepath=false ls-files -co --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Unable to enumerate repository files for secret scanning.' }
foreach ($relative in $paths | Sort-Object -Unique) {
    $normalized = $relative.Replace('\', '/')
    if (-not $IncludeTestFixture -and $normalized -eq $fixture) { continue }
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    if ([System.IO.Path]::GetExtension($path).ToLowerInvariant() -in $binaryExtensions) { continue }
    if ([System.IO.Path]::GetFileName($path) -match '^\.env(?:\.|$)' -and
            [System.IO.Path]::GetFileName($path) -ne '.env.example') {
        $findings.Add("$normalized [tracked-env-file]")
        continue
    }
    try {
        Test-Text $normalized ([System.IO.File]::ReadAllText($path, [Text.Encoding]::UTF8))
    } catch [System.Text.DecoderFallbackException] {
        continue
    }
}

$diff = (& git -C $root diff --no-ext-diff --unified=0 HEAD -- .) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the current Git diff for secret scanning.' }
$added = (($diff -split "`n") | Where-Object {
    $_.StartsWith('+') -and -not $_.StartsWith('+++')
}) -join "`n"
Test-Text 'git-diff-added-lines' $added

if ($findings.Count -gt 0) {
    $locations = ($findings | Sort-Object -Unique) -join '; '
    throw "Potential secret material detected at $locations. Values are intentionally not printed."
}
Write-Output 'Secret check passed: tracked/current files and added diff lines contain no recognized secret shape.'
$global:LASTEXITCODE = 0
