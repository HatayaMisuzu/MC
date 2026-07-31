param(
    [Parameter(Mandatory = $true)][string]$ReleaseDir
)

$ErrorActionPreference = 'Stop'
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path.TrimEnd('\')
$errors = [System.Collections.Generic.List[string]]::new()

function Add-Error([string]$message) { $script:errors.Add($message) }

# Core user/developer documentation that must ship in every product release.
$required = @(
    'README.md',
    'README.txt',
    'CHANGELOG.md',
    'KNOWN_LIMITATIONS.md',
    'docs/INDEX.md',
    'docs/PRODUCT_STATUS.md',
    'docs/RUNTIME_SETUP.md',
    'docs/TROUBLESHOOTING.md',
    'docs/COMPATIBILITY.md',
    'docs/COMMANDS.md',
    'docs/CONTROL_TERMINAL.md',
    'docs/ARCHITECTURE.md',
    'docs/RC_COMPLETION_MATRIX.md',
    'docs/LIVE_BRAIN_HUMAN_PLAYTEST.md',
    'docs/POST_PRODUCTIZATION_P0.md',
    'docs/TASK_GRAPH_DSL.md',
    'docs/MCP_PROTOCOL.md',
    'docs/PRIMITIVE_TOOLS.md',
    'docs/AGENT_WORKSPACE.md',
    'docs/INSTALL_FABRIC_1.21.1.md',
    'docs/INSTALL_FORGE_1.20.1.md',
    'docs/INSTALL_NEOFORGE_1.21.1.md',
    'docs/user/USER_GUIDE.zh-CN.md',
    'docs/user/USER_GUIDE.en-US.md',
    'docs/developer/README.md',
    'docs/compatibility/MCAC_COMPATIBILITY_LAYER_ENGINEERING.md',
    'docs/compatibility/FIRST_REAL_PACK_ACCEPTANCE.md',
    'docs/product/PRODUCT_TRUTH.json',
    'docs/product/BRAIN_ADAPTER_CAPABILITIES.json',
    'docs/product/BUILTIN_SKILL_SCOPE.md',
    'docs/product/TERMINOLOGY.md'
)
foreach ($item in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $release $item) -PathType Leaf)) {
        Add-Error "release documentation missing: $item"
    }
}

# README.txt must be generated from README.md so the two cannot drift.
$readmeMd = Join-Path $release 'README.md'
$readmeTxt = Join-Path $release 'README.txt'
if ((Test-Path -LiteralPath $readmeMd) -and (Test-Path -LiteralPath $readmeTxt)) {
    $md = (Get-Content -Raw -Encoding UTF8 -LiteralPath $readmeMd).Trim()
    $txt = (Get-Content -Raw -Encoding UTF8 -LiteralPath $readmeTxt).Trim()
    if ($md -cne $txt) { Add-Error 'README.txt is not an exact copy of README.md' }
}

# Repository-internal execution rules, historical reports and archived evidence
# must never ship inside the product release. They remain in the source tree only.
$forbiddenPrefixes = @(
    'docs/execution/',
    'docs/archive/',
    'docs/adr/',
    'docs/design/',
    'docs/human-test/'
)
$forbiddenFiles = @(
    'AGENTS.md',
    'CODEX_EXECUTION.md',
    'CODEX_GOAL.md',
    'FINAL_REPORT.md',
    'docs/GOAL_STATE.md',
    'docs/EXTERNAL_BRAIN_STATE.md',
    'docs/TEXT_COMPANION_IMPLEMENTATION_STATUS.md',
    'docs/PRODUCTIZATION_AUDIT_V5.md',
    'docs/product/PRODUCTIZATION_BASELINE.md',
    'docs/product/PRODUCTIZATION_CLOSEOUT_REPORT.md',
    'docs/product/REPOSITORY_PRODUCTIZATION_AUDIT.md',
    'docs/product/MCAC_0.3.1_FULL_AUDIT_AND_REPAIR_REPORT.md',
    'docs/product/UI_INTERACTION_MATRIX.md',
    'docs/product/UI_INTERACTION_MATRIX.json'
)

function Test-Forbidden([string]$Relative) {
    foreach ($prefix in $forbiddenPrefixes) {
        if ($Relative.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    foreach ($name in $forbiddenFiles) {
        if ($Relative -eq $name) { return $true }
    }
    return $false
}

foreach ($file in (Get-ChildItem -LiteralPath $release -Recurse -File)) {
    $relative = $file.FullName.Substring($release.Length).TrimStart('\').Replace('\', '/')
    if (Test-Forbidden $relative) {
        Add-Error "forbidden internal/historical file in release: $relative"
    }
}

# Verify every local relative Markdown link inside the release against the
# real extracted filesystem. External URLs, mailto links and pure #anchors are
# skipped; the path part is URL-decoded before resolution.
$markdown = @(Get-ChildItem -LiteralPath $release -Recurse -File -Filter '*.md')
foreach ($file in $markdown) {
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
    $relativeSource = $file.FullName.Substring($release.Length).TrimStart('\').Replace('\', '/')
    # runtime/legal/ contains JDK-shipped license pages, not product documentation;
    # they are static and must not become part of the release link contract.
    if ($relativeSource.StartsWith('runtime/legal/', [StringComparison]::OrdinalIgnoreCase)) { continue }
    foreach ($match in [regex]::Matches($text, '\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value.Trim()
        if ($target -match '^(https?|mailto):' -or $target.StartsWith('#')) { continue }
        $pathPart = ($target -split '#', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathPart)) { continue }
        if ($pathPart -match '^(?:[A-Za-z]:[\\/]|/Users/|/home/|//)') {
            Add-Error "${relativeSource}: absolute path link: $target"
            continue
        }
        $decoded = [uri]::UnescapeDataString($pathPart)
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $decoded))
        if (-not $resolved.StartsWith($release + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            Add-Error "${relativeSource}: link escapes release directory: $target"
            continue
        }
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            $relativeTarget = $resolved.Substring($release.Length).TrimStart('\').Replace('\', '/')
            Add-Error "${relativeSource}: missing link target: $target (resolved release path: $relativeTarget)"
            continue
        }
        $relativeTarget = $resolved.Substring($release.Length).TrimStart('\').Replace('\', '/')
        if (Test-Forbidden $relativeTarget) {
            Add-Error "${relativeSource}: link points to forbidden internal/historical file: $target"
        }
    }
}

if ($errors.Count -gt 0) {
    # Windows PowerShell 5.1 treats Write-Error as a terminating error under
    # ErrorActionPreference=Stop, which would hide every error after the first.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $errors | ForEach-Object { Write-Error $_ } }
    finally { $ErrorActionPreference = $previous }
    exit 1
}

Write-Host "Release documentation check passed: $($markdown.Count) Markdown files, $($required.Count) required documents, links and release boundary verified in $release."
$global:LASTEXITCODE = 0
