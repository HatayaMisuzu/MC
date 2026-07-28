param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$version = '9.6.1'
$expectedSha256 = '9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$probeRoot = Join-Path $root 'artifacts\codex-verification\gradle-probe'
$archive = Join-Path $probeRoot "gradle-$version-bin.zip"
$distribution = Join-Path $probeRoot "gradle-$version"
$launcher = Join-Path $distribution 'bin\gradle.bat'
New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null

if (-not (Test-Path -LiteralPath $archive) -or
        (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha256) {
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" `
        -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha256) {
    throw 'Gradle 9 probe distribution SHA-256 mismatch.'
}
if (-not (Test-Path -LiteralPath $launcher)) {
    Expand-Archive -LiteralPath $archive -DestinationPath $probeRoot -Force
}

& $launcher -p $root check --warning-mode all --stacktrace --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Gradle $version compatibility probe passed."
