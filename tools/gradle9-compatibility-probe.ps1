[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$version = '9.6.1'
$expectedSha256 = '9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$probeRoot = Join-Path $root 'artifacts\codex-verification\gradle-probe'
$archive = Join-Path $probeRoot "gradle-$version-bin.zip"
$distribution = Join-Path $probeRoot "gradle-$version"
$launcher = Join-Path $distribution 'bin\gradle.bat'
$distributionUri = "https://services.gradle.org/distributions/gradle-$version-bin.zip"

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Path)

    $stream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    try {
        $algorithm = [System.Security.Cryptography.SHA256]::Create()
        try {
            $bytes = $algorithm.ComputeHash($stream)
            return ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
        }
        finally {
            $algorithm.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Test-ExpectedArchive {
    if (-not [System.IO.File]::Exists($archive)) {
        return $false
    }

    try {
        return (Get-Sha256Hex -Path $archive) -eq $expectedSha256
    }
    catch {
        return $false
    }
}

function Download-VerifiedArchive {
    New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null
    $temporary = Join-Path $probeRoot (
        "gradle-$version-bin.$PID.$([System.Guid]::NewGuid().ToString('N')).download")

    try {
        Add-Type -AssemblyName System.Net.Http
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.AllowAutoRedirect = $true
        $client = [System.Net.Http.HttpClient]::new($handler)
        try {
            $client.Timeout = [System.TimeSpan]::FromMinutes(10)
            $response = $client.GetAsync(
                $distributionUri,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
            ).GetAwaiter().GetResult()
            try {
                [void]$response.EnsureSuccessStatusCode()
                $input = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                try {
                    $output = [System.IO.File]::Open(
                        $temporary,
                        [System.IO.FileMode]::CreateNew,
                        [System.IO.FileAccess]::Write,
                        [System.IO.FileShare]::None)
                    try {
                        $input.CopyTo($output)
                        $output.Flush()
                    }
                    finally {
                        $output.Dispose()
                    }
                }
                finally {
                    $input.Dispose()
                }
            }
            finally {
                $response.Dispose()
            }
        }
        finally {
            $client.Dispose()
            $handler.Dispose()
        }

        $downloadedSha256 = Get-Sha256Hex -Path $temporary
        if ($downloadedSha256 -ne $expectedSha256) {
            throw "Gradle $version distribution SHA-256 mismatch. Expected $expectedSha256, actual $downloadedSha256."
        }

        if ([System.IO.File]::Exists($archive)) {
            [System.IO.File]::Delete($archive)
        }
        [System.IO.File]::Move($temporary, $archive)
    }
    finally {
        if ([System.IO.File]::Exists($temporary)) {
            [System.IO.File]::Delete($temporary)
        }
    }
}

function Expand-VerifiedDistribution {
    if ([System.IO.Directory]::Exists($distribution)) {
        [System.IO.Directory]::Delete($distribution, $true)
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($archive, $probeRoot)

    if (-not [System.IO.File]::Exists($launcher)) {
        throw "Gradle $version launcher is missing after verified extraction: $launcher"
    }
}

function Invoke-DocumentationGate {
    param([Parameter(Mandatory = $true)][string]$ScriptPath)

    $powerShell = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' +
        $ScriptPath.Replace('"', '\"') + '" -RepositoryRoot "' +
        $root.Replace('"', '\"') + '"'
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $powerShell
    $startInfo.Arguments = $arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Unable to start documentation gate: $ScriptPath"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Host $stdout.TrimEnd()
        }
        if ($process.ExitCode -ne 0) {
            throw "Documentation gate failed ($($process.ExitCode)): $ScriptPath`n$stderr"
        }
    }
    finally {
        $process.Dispose()
    }
}

New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null

if (-not (Test-ExpectedArchive)) {
    Download-VerifiedArchive
}
if ((Get-Sha256Hex -Path $archive) -ne $expectedSha256) {
    throw 'Gradle 9 probe distribution SHA-256 mismatch after download.'
}
if (-not [System.IO.File]::Exists($launcher)) {
    Expand-VerifiedDistribution
}

# Run the PowerShell-backed documentation gates directly. On GitHub Windows,
# nesting them under Gradle 9's process-output provider can discard their
# diagnostics and report a spurious process failure even though both scripts
# pass in the exact PR merge tree. The assertions remain mandatory here; only
# the unreliable nested process wrapper is excluded from the Gradle invocation.
Invoke-DocumentationGate -ScriptPath (Join-Path $root 'tools\documentation-check.ps1')
Invoke-DocumentationGate -ScriptPath (Join-Path $root 'tools\documentation-check-negative.ps1')

& $launcher -p $root check -x documentationCheck `
    --warning-mode all --stacktrace --no-daemon --no-parallel
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Gradle $version compatibility probe passed with verified distribution SHA-256 $expectedSha256."
