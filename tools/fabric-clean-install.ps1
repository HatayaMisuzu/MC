[CmdletBinding()]
param(
    [switch]$WithLithium
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$variant = if ($WithLithium) { 'fabric-1.21.1-lithium' } else { 'fabric-1.21.1' }
$instance = Join-Path $root "build\clean-install\$variant"
$evidence = Join-Path $root "build\clean-install-evidence\$variant"
$installer = Join-Path $root 'build\downloads\fabric-installer-1.1.1.jar'
$modJar = Get-ChildItem -LiteralPath (Join-Path $root 'minecraft\fabric-1.21.1\build\libs') -Filter '*.jar' -File |
    Where-Object { $_.Name -notmatch '(-sources|-dev|-shadow)\.jar$' } | Select-Object -First 1
$fabricApi = Get-ChildItem -Path "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\0.116.13+1.21.1" `
    -Recurse -Filter 'fabric-api-0.116.13+1.21.1.jar' -File | Select-Object -First 1
$java = Get-ChildItem -Path "$env:USERPROFILE\.gradle\jdks" -Directory -Filter 'eclipse_adoptium-21*' |
    ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

if (-not $modJar) { throw 'Fabric production JAR is missing; run the Fabric build first.' }
if (-not $fabricApi) { throw 'Fabric API 0.116.13+1.21.1 is missing from the verified Gradle cache.' }
if (-not $java) { throw 'A Java 21 Gradle toolchain was not found.' }
if (Test-Path -LiteralPath $instance) { Remove-Item -LiteralPath $instance -Recurse -Force }
if (Test-Path -LiteralPath $evidence) { Remove-Item -LiteralPath $evidence -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $installer), $instance, $evidence | Out-Null

if (-not (Test-Path -LiteralPath $installer)) {
    Invoke-WebRequest -UseBasicParsing `
        -Uri 'https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.1/fabric-installer-1.1.1.jar' `
        -OutFile $installer
}

& $java -jar $installer server -dir $instance -mcversion 1.21.1 -loader 0.19.3 -downloadMinecraft *> (Join-Path $evidence 'installer.log')
if ($LASTEXITCODE -ne 0) { throw 'Fabric installer failed.' }
New-Item -ItemType Directory -Force -Path (Join-Path $instance 'mods') | Out-Null
Copy-Item -LiteralPath $modJar.FullName, $fabricApi.FullName -Destination (Join-Path $instance 'mods') -Force
if ($WithLithium) {
    $headers = @{ 'User-Agent' = 'Minecraft-AI-Companion-Alpha-Compatibility-Test/0.1' }
    $versions = Invoke-RestMethod -Headers $headers -Uri 'https://api.modrinth.com/v2/project/lithium/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D'
    $release = $versions | Where-Object { $_.version_type -eq 'release' } | Select-Object -First 1
    if (-not $release) { throw 'No released Fabric Lithium build for Minecraft 1.21.1 was returned by Modrinth.' }
    $file = $release.files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $file) { $file = $release.files | Select-Object -First 1 }
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $file.url -OutFile (Join-Path $instance "mods\$($file.filename)")
    [IO.File]::WriteAllText((Join-Path $evidence 'lithium-version.txt'),
        "name=$($release.name)`nversion=$($release.version_number)`nfile=$($file.filename)`nsha512=$($file.hashes.sha512)`n",
        [Text.UTF8Encoding]::new($false))
}
[IO.File]::WriteAllText((Join-Path $instance 'eula.txt'), "eula=true`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $instance 'server.properties'), "online-mode=false`nlevel-name=clean-world`n", [Text.UTF8Encoding]::new($false))

$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $java
$start.Arguments = '-Xmx2G -jar fabric-server-launch.jar nogui'
$start.WorkingDirectory = $instance
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$server = [Diagnostics.Process]::new()
$server.StartInfo = $start
if (-not $server.Start()) { throw 'Unable to start clean Fabric server.' }
$stdoutTask = $server.StandardOutput.ReadToEndAsync()
$stderrTask = $server.StandardError.ReadToEndAsync()
try {
    $latestLog = Join-Path $instance 'logs\latest.log'
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        if ($server.HasExited) { throw "Clean Fabric server exited early ($($server.ExitCode))." }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Clean Fabric server did not become ready in time.' }
        Start-Sleep -Milliseconds 500
        $ready = (Test-Path -LiteralPath $latestLog) -and ((Get-Content -Raw -LiteralPath $latestLog) -match 'Done \([0-9.]+s\)!')
    } until ($ready)
    $server.StandardInput.WriteLine('stop')
    $server.StandardInput.Flush()
    if (-not $server.WaitForExit(30000)) { throw 'Clean Fabric server did not stop in time.' }
} finally {
    if (-not $server.HasExited) { $server.Kill($true) }
}

$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()
[IO.File]::WriteAllText((Join-Path $evidence 'server.out.log'), $stdout, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $evidence 'server.err.log'), $stderr, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $instance 'logs\latest.log') -Destination (Join-Path $evidence 'latest.log') -Force
$latest = Get-Content -Raw -LiteralPath (Join-Path $evidence 'latest.log')
if ($server.ExitCode -ne 0) { throw "Clean Fabric server stopped with exit code $($server.ExitCode)." }
if ($latest -notmatch 'Minecraft AI Companion Fabric initialized') { throw 'Production companion JAR did not initialize in the clean server.' }
if ($WithLithium -and $latest -notmatch '(?m)^\s*- lithium ') { throw 'Lithium was not loaded in the compatibility instance.' }
if ($latest -notmatch 'Saving chunks for level') { throw 'Clean server did not save its world during shutdown.' }
if ($latest -match '(?i)(Encountered an unexpected exception|NoClassDefFoundError|mod resolution encountered an incompatible mod set|failed to start|mixin apply failed)') {
    throw 'Clean server log contains a loader or Mixin failure.'
}
$label = if ($WithLithium) { ' with Lithium' } else { '' }
Write-Output "Clean Fabric production-JAR install$label passed: installer, Fabric API, world ready, mod initialized, save, normal stop."
