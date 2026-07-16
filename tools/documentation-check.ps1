param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($RepositoryRoot)
$errors = [System.Collections.Generic.List[string]]::new()
$markdown = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notmatch '[\\/](build|node_modules|\.gradle)[\\/]' }

foreach ($file in $markdown) {
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
    foreach ($match in [regex]::Matches($text, '\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value.Trim()
        if ($target -match '^(https?|mailto):' -or $target.StartsWith('#')) { continue }
        $pathPart = ($target -split '#', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathPart)) { continue }
        $decoded = [uri]::UnescapeDataString($pathPart)
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $decoded))
        if (-not $resolved.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
            $errors.Add("$($file.FullName): link escapes repository: $target")
        } elseif (-not (Test-Path -LiteralPath $resolved)) {
            $errors.Add("$($file.FullName): missing link target: $target")
        }
    }
}

$required = @(
    'README.md',
    'AGENTS.md',
    'CODEX_EXECUTION.md',
    'docs/ARCHITECTURE.md',
    'docs/RC_COMPLETION_MATRIX.md',
    'docs/TASK_GRAPH_DSL.md',
    'docs/MCP_PROTOCOL.md',
    'runtime/runtime-app/src/main/java/com/mccompanion/runtime/taskgraph/TaskGraphValidator.java'
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relative))) {
        $errors.Add("missing documented repository path: $relative")
    }
}

$buildFile = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root 'build.gradle')
$documentedCommands = @(
    'check',
    'buildPlatforms',
    'runtimeFabricE2E',
    'persistenceRestartTest',
    'verifyTerminalPackage',
    'htmlTerminalStartTest'
)
foreach ($task in $documentedCommands) {
    if ($task -eq 'check') { continue }
    if ($buildFile -notmatch "tasks\.register\('$([regex]::Escape($task))'") {
        $errors.Add("README documents missing Gradle task: $task")
    }
}

$currentDocs = @(
    'README.md',
    'AGENTS.md',
    'CODEX_EXECUTION.md',
    'KNOWN_LIMITATIONS.md',
    'docs/ARCHITECTURE.md',
    'docs/RC_COMPLETION_MATRIX.md',
    'docs/EXTERNAL_BRAIN_STATE.md'
)
$forbiddenLabels = @(
    'READY_FOR_HUMAN_PRODUCT_TEST',
    'READY_FOR_HUMAN_PRODUCT_TEST_EXCEPT_LIVE_PROVIDER',
    'READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST`'
)
foreach ($relative in $currentDocs) {
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root $relative)
    foreach ($label in $forbiddenLabels) {
        if ($text.Contains($label)) {
            $errors.Add("$relative contains obsolete readiness label: $label")
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Documentation check passed: $($markdown.Count) Markdown files, links, paths, commands, and readiness labels verified."
