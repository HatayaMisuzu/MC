param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$check = Join-Path $PSScriptRoot 'release-documentation-check.ps1'
$work = Join-Path $env:TEMP ('mcac-release-doc-fixture-' + [Guid]::NewGuid().ToString('N'))
$base = Join-Path $work 'baseline'

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

function New-Fixture {
    New-Item -ItemType Directory -Path $base -Force | Out-Null
    foreach ($item in $required) {
        $path = Join-Path $base $item
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        Set-Content -LiteralPath $path -Value 'placeholder' -Encoding UTF8
    }
    $readme = @'
# Fixture release

[User guide zh](docs/user/USER_GUIDE.zh-CN.md)

[Product status](docs/PRODUCT_STATUS.md)

[Known limitations](KNOWN_LIMITATIONS.md)
'@
    Set-Content -LiteralPath (Join-Path $base 'README.md') -Value $readme -Encoding UTF8
    Copy-Item -LiteralPath (Join-Path $base 'README.md') -Destination (Join-Path $base 'README.txt') -Force
}

function Invoke-Check([string]$Dir) {
    # Windows PowerShell 5.1 turns native stderr records redirected with 2>&1 into
    # terminating errors under ErrorActionPreference=Stop, which would abort the
    # negative harness on the expected Write-Error output of the check script.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $check -ReleaseDir $Dir 2>&1
        return @{ Code = $LASTEXITCODE; Output = ($output -join "`n") }
    }
    finally { $ErrorActionPreference = $previous }
}

function Assert-Negative([string]$Name, [string]$Dir, [string]$ExpectedText) {
    $result = Invoke-Check $Dir
    if ($result.Code -eq 0) {
        throw "Negative case '$Name' was not detected by the release documentation check."
    }
    if ($result.Output -notmatch [regex]::Escape($ExpectedText)) {
        throw "Negative case '$Name' failed for the wrong reason. Expected text: $ExpectedText. Output: $($result.Output)"
    }
    Write-Host "Negative case passed: $Name"
}

try {
    New-Fixture

    $baseline = Invoke-Check $base
    if ($baseline.Code -ne 0) {
        throw "Baseline fixture unexpectedly failed the release documentation check: $($baseline.Output)"
    }
    Write-Host 'Positive baseline passed: intact fixture is accepted.'

    # 1. Deleting a Markdown-linked target file must fail the check.
    $case = Join-Path $work 'case1-deleted-link-target'
    Copy-Item -LiteralPath $base -Destination $case -Recurse
    Remove-Item -LiteralPath (Join-Path $case 'docs/user/USER_GUIDE.zh-CN.md') -Force
    Assert-Negative 'deleted link target' $case 'missing link target'

    # 2. A link escaping the release directory must fail the check.
    $case = Join-Path $work 'case2-escaping-link'
    Copy-Item -LiteralPath $base -Destination $case -Recurse
    Add-Content -LiteralPath (Join-Path $case 'README.md') -Value "`n[Escape](../../outside.md)" -Encoding UTF8
    Assert-Negative 'escaping link' $case 'escapes release directory'

    # 3. A forbidden internal file placed inside the release must fail the check.
    $case = Join-Path $work 'case3-forbidden-file'
    Copy-Item -LiteralPath $base -Destination $case -Recurse
    Set-Content -LiteralPath (Join-Path $case 'AGENTS.md') -Value '# internal agent rules' -Encoding UTF8
    Assert-Negative 'forbidden file' $case 'forbidden internal/historical file in release: AGENTS.md'

    # 4. Removing a core user guide must fail the check.
    $case = Join-Path $work 'case4-missing-user-guide'
    Copy-Item -LiteralPath $base -Destination $case -Recurse
    Remove-Item -LiteralPath (Join-Path $case 'docs/user/USER_GUIDE.en-US.md') -Force
    Assert-Negative 'missing user guide' $case 'release documentation missing: docs/user/USER_GUIDE.en-US.md'

    # 5. A link to a repository-only internal file (not in the release) must fail.
    $case = Join-Path $work 'case5-repo-only-link'
    Copy-Item -LiteralPath $base -Destination $case -Recurse
    Add-Content -LiteralPath (Join-Path $case 'README.md') -Value "`n[Execution contract](CODEX_EXECUTION.md)" -Encoding UTF8
    Assert-Negative 'repo-only link' $case 'missing link target'
}
finally {
    if (Test-Path -LiteralPath $work) {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host 'Release documentation negative check passed: all five boundary violations were rejected.'
$global:LASTEXITCODE = 0
