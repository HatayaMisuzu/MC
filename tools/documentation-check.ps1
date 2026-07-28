param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ProductTruthOverride = ''
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($RepositoryRoot)
$errors = [System.Collections.Generic.List[string]]::new()

function Add-Error([string]$message) { $script:errors.Add($message) }
function Read-Repo([string]$relative) {
    Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root $relative)
}

$excluded = '[\\/](build|node_modules|\.gradle|artifacts|\.git)[\\/]'
$markdown = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' |
    Where-Object { $_.FullName -notmatch $excluded }

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
            Add-Error "$($file.FullName): link escapes repository: $target"
        } elseif (-not (Test-Path -LiteralPath $resolved)) {
            Add-Error "$($file.FullName): missing link target: $target"
        }
    }
}

$required = @(
    'README.md', 'AGENTS.md', 'CODEX_EXECUTION.md', 'KNOWN_LIMITATIONS.md', 'CHANGELOG.md',
    'NOTICE', 'docs/INDEX.md', 'docs/PRODUCT_STATUS.md', 'docs/product/PRODUCT_TRUTH.json',
    'docs/ARCHITECTURE.md', 'docs/COMPATIBILITY.md', 'docs/RUNTIME_SETUP.md',
    'docs/RC_COMPLETION_MATRIX.md', 'docs/user/USER_GUIDE.zh-CN.md',
    'docs/user/USER_GUIDE.en-US.md', 'docs/developer/README.md', 'docs/archive/INDEX.md',
    'docs/TASK_GRAPH_DSL.md', 'docs/MCP_PROTOCOL.md',
    'docs/human-test/INSTANCE_AUDIT_TEMPLATE.md',
    'runtime/runtime-app/src/main/java/com/mccompanion/runtime/taskgraph/TaskGraphValidator.java'
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relative))) {
        Add-Error "missing documented repository path: $relative"
    }
}

$adapterMatrixPath = Join-Path $root 'docs/product/BRAIN_ADAPTER_CAPABILITIES.json'
try {
    $adapterMatrix = Get-Content -Raw -Encoding UTF8 -LiteralPath $adapterMatrixPath | ConvertFrom-Json
    if ($adapterMatrix.schemaVersion -ne 1 -or $adapterMatrix.productVersion -ne '0.3.1') {
        Add-Error 'Brain Adapter capability matrix schema or product version is invalid'
    }
    $allowedAdapterEvidence = @('SUPPORTED', 'PARTIAL', 'NOT_SUPPORTED', 'TEST_ONLY', 'PENDING_EXTERNAL')
    $adapterFields = @('toolCalling', 'semanticState', 'completionClaim', 'askUser',
        'reconnectResume', 'initiativePersonality', 'mcpNativeConfiguration', 'liveEvidence')
    $adaptersById = @{}
    foreach ($adapter in @($adapterMatrix.adapters)) {
        $adaptersById[$adapter.id] = $adapter
        foreach ($field in $adapterFields) {
            if ($allowedAdapterEvidence -notcontains $adapter.$field) {
                Add-Error "Brain Adapter matrix has invalid $field status for $($adapter.id)"
            }
        }
    }
    if (($adaptersById.Keys | Sort-Object) -join ',' -ne 'hermes,openai-compatible,replay') {
        Add-Error 'Brain Adapter matrix must contain exactly Hermes, OpenAI-compatible and Replay'
    }
    if ($adaptersById['hermes'].liveEvidence -ne 'PENDING_EXTERNAL' -or
            $adaptersById['hermes'].mcpNativeConfiguration -ne 'PENDING_EXTERNAL') {
        Add-Error 'Hermes Live and MCP-native evidence must remain pending external verification'
    }
    if ($adaptersById['openai-compatible'].semanticState -ne 'NOT_SUPPORTED' -or
            $adaptersById['openai-compatible'].completionClaim -ne 'NOT_SUPPORTED') {
        Add-Error 'OpenAI-compatible semantic state and completion claims must remain unsupported'
    }
    if ($adaptersById['replay'].liveEvidence -ne 'NOT_SUPPORTED') {
        Add-Error 'Replay must never be represented as Live evidence'
    }
} catch {
    Add-Error "Brain Adapter capability matrix is missing or invalid JSON: $($_.Exception.Message)"
}

if (Test-Path -LiteralPath (Join-Path $root 'docs/human-test/INSTANCE_AUDIT.md')) {
    Add-Error 'tracked personal instance audit must not exist in the current tree'
}

$truthPath = if ($ProductTruthOverride) {
    [System.IO.Path]::GetFullPath((Join-Path $root $ProductTruthOverride))
} else {
    Join-Path $root 'docs/product/PRODUCT_TRUTH.json'
}
try {
    $truth = Get-Content -Raw -Encoding UTF8 -LiteralPath $truthPath | ConvertFrom-Json
} catch {
    Add-Error "PRODUCT_TRUTH.json is not valid JSON: $($_.Exception.Message)"
    $truth = $null
}

if ($null -ne $truth) {
    if ($truth.schemaVersion -ne 'mcac-product-truth/1') { Add-Error 'unsupported product truth schema' }
    if ($truth.productVersion -ne '0.3.1') { Add-Error 'product truth version must be 0.3.1' }
    if ($truth.readiness -ne 'READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC') { Add-Error 'product truth readiness is invalid' }
    if ($truth.automatedBaseline -ne 'FROZEN') { Add-Error 'product truth automated baseline must be FROZEN' }
    $expectedLoaders = @{
        'fabric-1.21.1' = @{ java = 21; mode = 'FULL_RUNTIME_BRIDGE' }
        'forge-1.20.1' = @{ java = 17; mode = 'FULL_RUNTIME_BRIDGE' }
        'neoforge-1.21.1' = @{ java = 21; mode = 'LOCAL_ONLY' }
    }
    foreach ($name in $expectedLoaders.Keys) {
        $actual = $truth.loaders.$name
        if ($null -eq $actual -or $actual.java -ne $expectedLoaders[$name].java -or
                $actual.mode -ne $expectedLoaders[$name].mode) {
            Add-Error "product truth Loader mismatch: $name"
        }
    }
    if ($truth.runtimeProfilePortRange.first -ne 8766 -or $truth.runtimeProfilePortRange.last -ne 8866) {
        Add-Error 'managed Runtime profile range must be 8766..8866'
    }
    $pending = @($truth.pendingExternalEvidence)
    foreach ($label in @('LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING', 'HUMAN_PLAYTEST_PENDING')) {
        if ($pending -notcontains $label) { Add-Error "missing external-evidence label: $label" }
    }
}

$versionDocs = @(
    'README.md', 'KNOWN_LIMITATIONS.md', 'docs/PRODUCT_STATUS.md',
    'docs/user/USER_GUIDE.zh-CN.md', 'docs/user/USER_GUIDE.en-US.md'
)
foreach ($relative in $versionDocs) {
    if ((Read-Repo $relative) -notmatch '(?<!\d)0\.3\.1(?!\d)') {
        Add-Error "$relative does not identify product version 0.3.1"
    }
}

$supportDocs = @('README.md', 'docs/PRODUCT_STATUS.md', 'docs/COMPATIBILITY.md',
    'KNOWN_LIMITATIONS.md', 'docs/user/USER_GUIDE.zh-CN.md', 'docs/user/USER_GUIDE.en-US.md')
foreach ($relative in $supportDocs) {
    $text = Read-Repo $relative
    if ($text -notmatch '(?is)(?:Fabric.{0,80}1\.21\.1|1\.21\.1.{0,80}Fabric).{0,220}(?:FULL_RUNTIME_BRIDGE|FULL Runtime Bridge|Full Runtime Bridge)') {
        Add-Error "$relative does not declare Fabric Full Runtime Bridge"
    }
    if ($text -notmatch '(?is)(?:Forge.{0,80}1\.20\.1|1\.20\.1.{0,80}Forge).{0,220}(?:FULL_RUNTIME_BRIDGE|FULL Runtime Bridge|Full Runtime Bridge)') {
        Add-Error "$relative does not declare Forge Full Runtime Bridge"
    }
    if ($text -notmatch '(?is)(?:NeoForge.{0,80}1\.21\.1|1\.21\.1.{0,80}NeoForge).{0,220}LOCAL_ONLY') {
        Add-Error "$relative does not declare NeoForge LOCAL_ONLY"
    }
    if ($text -match '(?im)Forge\s+1\.20\.1[^\r\n]{0,160}(?:LOCAL_ONLY|不启用外部\s*Runtime|no\s+Runtime)') {
        Add-Error "$relative incorrectly limits Forge Runtime support"
    }
    if ($text -match '(?im)NeoForge\s+1\.21\.1[^\r\n]{0,160}`?FULL_RUNTIME_BRIDGE`?') {
        Add-Error "$relative incorrectly claims NeoForge Full Runtime Bridge"
    }
}

$matrixHead = ((Get-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'docs/RC_COMPLETION_MATRIX.md') |
    Select-Object -First 25) -join "`n")
foreach ($requiredText in @(
    'READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC', 'Automated productization baseline: `FROZEN`',
    'Product version: `0.3.1`', 'mcac-productization-baseline-0.3.0',
    'LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING', 'HUMAN_PLAYTEST_PENDING'
)) {
    if (-not $matrixHead.Contains($requiredText)) { Add-Error "RC matrix header missing: $requiredText" }
}

$runtimeSetup = Read-Repo 'docs/RUNTIME_SETUP.md'
if (($runtimeSetup -notmatch '8766.8866') -or
        (-not $runtimeSetup.Contains('first allocatable port'))) {
    Add-Error 'Runtime setup does not explain the managed 8766..8866 stable allocation range'
}

$currentDocs = @(
    'README.md', 'AGENTS.md', 'CODEX_EXECUTION.md', 'KNOWN_LIMITATIONS.md', 'CHANGELOG.md',
    'docs/INDEX.md', 'docs/PRODUCT_STATUS.md', 'docs/ARCHITECTURE.md', 'docs/COMPATIBILITY.md',
    'docs/RUNTIME_SETUP.md', 'docs/PRIMITIVE_TOOLS.md', 'docs/AGENT_WORKSPACE.md',
    'docs/MCP_PROTOCOL.md', 'docs/CONTROL_TERMINAL.md', 'docs/TROUBLESHOOTING.md',
    'docs/COMMANDS.md', 'docs/user/USER_GUIDE.zh-CN.md', 'docs/user/USER_GUIDE.en-US.md',
    'docs/developer/README.md'
)
$obsolete = @(
    'Stage 3 in progress', 'final readiness label not assigned', 'Merge allowed:\s*NO',
    '(?:Productization )?baseline created:\s*NO', 'Fabric-first RC',
    'READY_FOR_HUMAN_PRODUCT_TEST(?:_EXCEPT_LIVE_PROVIDER)?',
    'READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST(?!_RC)'
)
foreach ($relative in $currentDocs) {
    $text = Read-Repo $relative
    foreach ($pattern in $obsolete) {
        if ([regex]::IsMatch($text, $pattern, 'IgnoreCase')) {
            Add-Error "$relative contains obsolete current-status text matching: $pattern"
        }
    }
}

$historyFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $root 'docs/execution') -File -Filter '*.md' |
        Where-Object { $_.Name -ne 'MCAC_0.3.1_AUDIT_REPAIR_TRACKER.md' }
    Get-Item -LiteralPath (Join-Path $root 'docs/product/PRODUCTIZATION_BASELINE.md')
    Get-Item -LiteralPath (Join-Path $root 'docs/product/PRODUCTIZATION_CLOSEOUT_REPORT.md')
    Get-Item -LiteralPath (Join-Path $root 'docs/product/REPOSITORY_PRODUCTIZATION_AUDIT.md')
    Get-Item -LiteralPath (Join-Path $root 'docs/PRODUCTIZATION_AUDIT_V5.md')
    Get-Item -LiteralPath (Join-Path $root 'docs/EXTERNAL_BRAIN_STATE.md')
    Get-Item -LiteralPath (Join-Path $root 'FINAL_REPORT.md')
    Get-ChildItem -LiteralPath (Join-Path $root 'docs/adr') -File -Filter '*.md'
)
foreach ($file in $historyFiles) {
    $head = (Get-Content -Encoding UTF8 -LiteralPath $file.FullName | Select-Object -First 10) -join "`n"
    if ($head -notmatch '(?i)Status:\s*(?:Superseded|Historical)|Historical accepted decision') {
        Add-Error "$($file.FullName): historical document lacks a visible status marker in its first 10 lines"
    }
}

foreach ($file in $markdown) {
    $relative = $file.FullName.Substring($root.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
    if ($text -match '(?i)(?:[A-Z]:\\(?!path\\to\\|test_root|game_dir)|/Users/[^/\s`]+|/home/[^/\s`]+)') {
        Add-Error "$relative contains an absolute personal-machine path"
    }
    if ($text -match '(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}') {
        Add-Error "$relative contains an email-shaped value"
    }
    foreach ($match in [regex]::Matches($text, '\b(?:\d{1,3}\.){3}\d{1,3}\b')) {
        # 3.49.1.0 is the tracked SQLite JDBC dependency version, not an address.
        if ($match.Value -notin @('127.0.0.1', '0.0.0.0', '3.49.1.0')) {
            Add-Error "$relative contains a non-loopback IPv4-shaped value"
        }
    }
    if ($text -match '(?i)(?:launcher_profiles\.json|accounts\.json|launcher_accounts\.json)') {
        Add-Error "$relative contains a launcher account-file name"
    }
}

$changelog = Read-Repo 'CHANGELOG.md'
if ($changelog -notmatch '(?m)^## 0\.3\.1\s*$') { Add-Error 'CHANGELOG lacks a 0.3.1 section' }

foreach ($versionFile in @(
    'minecraft/fabric-1.21.1/gradle.properties',
    'minecraft/forge-1.20.1/gradle.properties',
    'minecraft/neoforge-1.21.1/gradle.properties'
)) {
    if ((Read-Repo $versionFile) -notmatch '(?m)^mod_version=0\.3\.1\s*$') {
        Add-Error "$versionFile does not declare mod_version=0.3.1"
    }
}
foreach ($productionFile in @(
    'tools/runtime-launcher.properties',
    'minecraft/fabric-1.21.1/src/gametest/resources/fabric.mod.json',
    'minecraft/fabric-1.21.1/src/main/java/com/mccompanion/minecraft/fabric/MinecraftAiCompanionFabric.java',
    'minecraft/forge-1.20.1/src/main/java/com/mccompanion/minecraft/forge/MinecraftAiCompanionForge.java',
    'minecraft/neoforge-1.21.1/src/main/java/com/mccompanion/minecraft/neoforge/MinecraftAiCompanionNeoForge.java',
    'runtime/runtime-app/src/main/java/com/mccompanion/runtime/health/RuntimeHealthServer.java',
    'runtime/runtime-app/src/main/java/com/mccompanion/runtime/websocket/RuntimeWebSocketServer.java',
    'terminal/terminal-app/src/main/java/com/mccompanion/terminal/ControlTerminalMain.java',
    'terminal/terminal-app/src/main/java/com/mccompanion/terminal/McpProtocolDoctor.java',
    'terminal/terminal-app/src/main/java/com/mccompanion/terminal/WebTerminalApi.java',
    'terminal/web-ui/src/components/AppShell.tsx'
)) {
    if ((Read-Repo $productionFile).Contains('0.3.0')) {
        Add-Error "$productionFile still contains the superseded product version 0.3.0"
    }
}

$buildFile = Read-Repo 'build.gradle'
if ($buildFile -match 'INSTANCE_AUDIT\.md') { Add-Error 'release packaging includes a personal instance audit' }
foreach ($releaseDoc in @('README.md', 'KNOWN_LIMITATIONS.md', 'docs/PRODUCT_STATUS.md',
    'docs/product/PRODUCT_TRUTH.json', 'docs/COMPATIBILITY.md', 'docs/ARCHITECTURE.md',
    'docs/MCP_PROTOCOL.md')) {
    if (-not $buildFile.Contains($releaseDoc)) { Add-Error "release packaging omits current document: $releaseDoc" }
}

$gitignore = Read-Repo '.gitignore'
if ($gitignore -match '(?m)^data/$') { Add-Error '.gitignore still has an unscoped data/ rule' }
if ($gitignore -notmatch '(?m)^/data/$') { Add-Error '.gitignore must ignore only repository-root /data/' }
foreach ($probe in @(
    'minecraft/fabric-1.21.1/src/gametest/resources/data/mcac-probe.txt',
    'minecraft/forge-1.20.1/src/gametest/resources/data/mcac-probe.txt',
    'minecraft/neoforge-1.21.1/src/gametest/resources/data/mcac-probe.txt'
)) {
    & git -C $root check-ignore --no-index -q -- $probe
    if ($LASTEXITCODE -eq 0) { Add-Error ".gitignore hides Loader resource path: $probe" }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Documentation check passed: $($markdown.Count) Markdown files; product truth, links, privacy, history, release docs, Loader resources and current status verified."
$global:LASTEXITCODE = 0
