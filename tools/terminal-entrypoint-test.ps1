param(
    [Parameter(Mandatory = $true)][string]$ReleaseDir,
    [Parameter(Mandatory = $true)][ValidateSet('tui', 'root', 'release')][string]$Mode
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path
$starterName = [string]([char]0x542F) + [char]0x52A8 + [char]0x7EC8 + [char]0x7AEF + '.cmd'

function Assert-Version([scriptblock]$Command, [string]$Name) {
    $output = & $Command 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $output -notmatch 'mcac 0\.3\.0') {
        throw "$Name did not return the mcac version. Output: $output"
    }
}

if ($Mode -eq 'tui') {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $release 'mcac-cli.exe'
    $start.Arguments = '--tui'
    $start.WorkingDirectory = $env:TEMP
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { throw 'Unable to start release TUI' }
    $process.StandardInput.WriteLine('0')
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(15000)) { $process.Kill(); throw 'TUI did not exit after safe cancellation' }
    $output = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    if ($process.ExitCode -ne 0) { throw "TUI exited $($process.ExitCode): $errorOutput" }
    foreach ($item in 0..8) {
        if ($output -notmatch [regex]::Escape("[$item]")) { throw "TUI menu item $item is missing" }
    }
    if ($output -match '\x1b\[') { throw 'TUI unexpectedly requires ANSI rendering' }
    Write-Output 'TUI integration test passed from an arbitrary working directory.'
    exit 0
}

if ($Mode -eq 'root') {
    Push-Location $env:TEMP
    try {
        Assert-Version { & (Join-Path $root 'mcac.cmd') --version } 'root mcac.cmd'
        Assert-Version { & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'mcac.ps1') --version } 'root mcac.ps1'
        Assert-Version { & (Join-Path $root $starterName) --version } 'root starter'
    } finally { Pop-Location }
    Write-Output 'Root launcher test passed from an arbitrary working directory.'
    exit 0
}

Push-Location $env:TEMP
try {
    Assert-Version { & (Join-Path $release 'mcac.cmd') --version } 'release mcac.cmd'
    Assert-Version { & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $release 'mcac.ps1') --version } 'release mcac.ps1'
    Assert-Version { & (Join-Path $release $starterName) --version } 'release starter'
} finally { Pop-Location }
Write-Output 'Release starter test passed from an arbitrary working directory.'
