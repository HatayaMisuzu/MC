param(
    [Parameter(Mandatory = $true)][string]$ReleaseDir,
    [Parameter(Mandatory = $true)][ValidateSet('tui', 'root', 'release')][string]$Mode
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path
$starterName = [string]([char]0x542F) + [char]0x52A8 + [char]0x7EC8 + [char]0x7AEF + '.cmd'

function Get-CanonicalVersion {
    $path = Join-Path $root 'gradle.properties'
    $lines = @(Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object { $_ -match '^\s*version\s*=\s*(?<value>[^\s#]+)\s*$' })
    if ($lines.Count -ne 1) { throw "Expected exactly one canonical version in $path; found $($lines.Count)" }
    [void]($lines[0] -match '^\s*version\s*=\s*(?<value>[^\s#]+)\s*$')
    $version = $Matches.value
    if ($version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') { throw "Unsupported canonical version: $version" }
    return $version
}
$expectedLine = "mcac $(Get-CanonicalVersion)"

function Stop-ProcessTree([Diagnostics.Process]$Process) {
    if ($Process.HasExited) { return }
    $kill = [Diagnostics.ProcessStartInfo]::new()
    $kill.FileName = 'taskkill.exe'
    $kill.Arguments = "/PID $($Process.Id) /T /F"
    $kill.UseShellExecute = $false
    $kill.CreateNoWindow = $true
    $kill.RedirectStandardOutput = $true
    $kill.RedirectStandardError = $true
    $killer = [Diagnostics.Process]::Start($kill)
    if ($killer) {
        [void]$killer.WaitForExit(5000)
        $killer.Dispose()
    }
    [void]$Process.WaitForExit(5000)
}

function Assert-Version(
    [string]$FileName,
    [string]$Arguments,
    [string]$Name
) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FileName
    $start.Arguments = $Arguments
    $start.WorkingDirectory = $env:TEMP
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Unable to start $Name" }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(20000)) {
            Stop-ProcessTree $process
            throw "$Name exceeded the 20-second process boundary"
        }
        $output = $stdoutTask.GetAwaiter().GetResult()
        $errorOutput = $stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    }
    finally {
        if (-not $process.HasExited) { Stop-ProcessTree $process }
        $process.Dispose()
    }
    $lines = @($output -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($exitCode -ne 0 -or $lines.Count -ne 1 -or $lines[0] -cne $expectedLine) {
        throw "$Name did not return canonical version '$expectedLine'. Exit=$exitCode Output: $output Error: $errorOutput"
    }
}

if ($Mode -eq 'tui') {
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=Join-Path $release 'mcac-cli.exe';$start.Arguments='--tui';$start.WorkingDirectory=$env:TEMP
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardInput=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    if(-not $process.Start()){throw 'Unable to start release TUI'}
    $process.StandardInput.WriteLine('0');$process.StandardInput.Close()
    if(-not $process.WaitForExit(15000)){Stop-ProcessTree $process;throw 'TUI did not exit after safe cancellation'}
    $output=$process.StandardOutput.ReadToEnd();$errorOutput=$process.StandardError.ReadToEnd()
    if($process.ExitCode -ne 0){throw "TUI exited $($process.ExitCode): $errorOutput"}
    foreach($item in 0..8){if($output -notmatch [regex]::Escape("[$item]")){throw "TUI menu item $item is missing"}}
    if($output -match '\x1b\['){throw 'TUI unexpectedly requires ANSI rendering'}
    Write-Output 'TUI integration test passed from an arbitrary working directory.';exit 0
}
if($Mode -eq 'root'){
    $cmd = Join-Path $root 'mcac.cmd'
    $ps1 = Join-Path $root 'mcac.ps1'
    $starter = Join-Path $root $starterName
    Assert-Version $env:ComSpec ('/d /s /c ""{0}" --version"' -f $cmd) 'root mcac.cmd'
    Assert-Version 'powershell.exe' ('-NoProfile -ExecutionPolicy Bypass -File "{0}" --version' -f $ps1) 'root mcac.ps1'
    Assert-Version $env:ComSpec ('/d /s /c ""{0}" --version"' -f $starter) 'root starter'
    Write-Output 'Root launcher test passed from an arbitrary working directory.';exit 0
}
$cmd = Join-Path $release 'mcac.cmd'
$ps1 = Join-Path $release 'mcac.ps1'
$starter = Join-Path $release $starterName
Assert-Version $env:ComSpec ('/d /s /c ""{0}" --version"' -f $cmd) 'release mcac.cmd'
Assert-Version 'powershell.exe' ('-NoProfile -ExecutionPolicy Bypass -File "{0}" --version' -f $ps1) 'release mcac.ps1'
Assert-Version $env:ComSpec ('/d /s /c ""{0}" --version"' -f $starter) 'release starter'
Write-Output 'Release starter test passed from an arbitrary working directory.'
