[CmdletBinding()]
param(
    [string]$PackageRoot
)

$ErrorActionPreference = 'Stop'
if (-not $PackageRoot) {
    $PackageRoot = Get-ChildItem -LiteralPath 'F:\' -Directory |
        Where-Object { $_.Name -like 'Minecraft_AI_Companion_Codex_*v1.0' } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $PackageRoot) { throw 'Execution package was not found on F:\.' }
$manifestPath = Join-Path $PackageRoot 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
$failures = @()
foreach ($property in $manifest.files.PSObject.Properties) {
    $relative = $property.Name.Replace('/', [IO.Path]::DirectorySeparatorChar)
    $path = Join-Path $PackageRoot $relative
    if (-not (Test-Path -LiteralPath $path)) {
        $failures += "missing: $relative"
        continue
    }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $property.Value.sha256) {
        $failures += "hash mismatch: $relative"
    }
}
if ($failures.Count) { throw ($failures -join [Environment]::NewLine) }
$fileCount = @($manifest.files.PSObject.Properties).Count
Write-Output "Execution package $($manifest.version) verified: $fileCount files match the manifest."
