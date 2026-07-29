param([string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = 'Stop'
$langRoot = Join-Path $RepositoryRoot 'minecraft/platform-common/src/main/resources/assets/minecraft_ai_companion/lang'
$englishPath = Join-Path $langRoot 'en_us.json'
$chinesePath = Join-Path $langRoot 'zh_cn.json'
$english = Get-Content -LiteralPath $englishPath -Raw -Encoding UTF8 | ConvertFrom-Json
$chinese = Get-Content -LiteralPath $chinesePath -Raw -Encoding UTF8 | ConvertFrom-Json
$englishKeys = @($english.PSObject.Properties.Name | Sort-Object)
$chineseKeys = @($chinese.PSObject.Properties.Name | Sort-Object)
if (Compare-Object $englishKeys $chineseKeys) {
    throw 'Localization key sets differ between en_us.json and zh_cn.json'
}

$sourceRoots = @(
    (Join-Path $RepositoryRoot 'minecraft/platform-1.20.1-common/src/main/java'),
    (Join-Path $RepositoryRoot 'minecraft/platform-1.21.1-common/src/main/java'),
    (Join-Path $RepositoryRoot 'minecraft/fabric-1.21.1/src/main/java'),
    (Join-Path $RepositoryRoot 'minecraft/forge-1.20.1/src/main/java')
)
$used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($file in Get-ChildItem -LiteralPath $sourceRoots -Recurse -Filter '*.java') {
    $raw = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    foreach ($match in [regex]::Matches($raw, 'Component\.translatable\("([^"]+)"')) {
        [void]$used.Add($match.Groups[1].Value)
    }
    if ($raw.Contains([char]0xfffd)) {
        throw "Mojibake remains in game source: $($file.FullName)"
    }
}
foreach ($key in $used) {
    if ($englishKeys -notcontains $key) { throw "Missing localization key: $key" }
}
Write-Host "Localization check passed: $($englishKeys.Count) bilingual keys; $($used.Count) referenced keys."
