param([Parameter(Mandatory = $true)][string]$ReleaseDir)

$ErrorActionPreference = 'Stop'
$release = (Resolve-Path -LiteralPath $ReleaseDir).Path
$state = Join-Path $env:TEMP ('mcac-html-state-' + [Guid]::NewGuid().ToString('N') + '.json')
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = Join-Path $release 'mcac.exe'
$start.WorkingDirectory = $env:TEMP
$start.UseShellExecute = $false
$start.Environment['MCAC_NO_BROWSER'] = 'true'
$start.Environment['MCAC_WEB_STATE_FILE'] = $state
$start.Environment['MCAC_WEB_ROOT'] = Join-Path $release 'web'
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start
if (-not $process.Start()) { throw 'Unable to start mcac.exe HTML terminal' }
Write-Output 'Primary mcac.exe started.'

try {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (-not (Test-Path -LiteralPath $state) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
    if (-not (Test-Path -LiteralPath $state)) { throw 'mcac.exe did not publish HTML terminal state' }
    Write-Output 'Dynamic loopback state published.'
    $server = Get-Content -LiteralPath $state -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($server.bind -ne '127.0.0.1' -or $server.port -le 0) { throw 'HTML server did not bind loopback on a dynamic port' }

    $secondStart = [Diagnostics.ProcessStartInfo]::new()
    $secondStart.FileName = Join-Path $release 'mcac.exe'
    $secondStart.WorkingDirectory = $env:TEMP
    $secondStart.UseShellExecute = $false
    $secondStart.Environment['MCAC_NO_BROWSER'] = 'true'
    $secondStart.Environment['MCAC_WEB_ROOT'] = Join-Path $release 'web'
    $second = [Diagnostics.Process]::Start($secondStart)
    Write-Output 'Second mcac.exe started for reuse check.'
    if (-not $second.WaitForExit(10000) -or $second.ExitCode -ne 0) {
        if (-not $second.HasExited) { $second.Kill() }
        throw 'Second mcac.exe did not reuse the existing per-user HTML terminal'
    }
    if ($process.HasExited) { throw 'Primary HTML terminal exited during single-instance reuse' }
    Write-Output 'Single-instance reuse passed.'

    Add-Type -AssemblyName System.Net.Http
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $bootstrap = $client.GetAsync([string]$server.bootstrapUrl).GetAwaiter().GetResult()
        if ([int]$bootstrap.StatusCode -ne 303) { throw "Bootstrap returned $([int]$bootstrap.StatusCode)" }
        $cookie = ($bootstrap.Headers.GetValues('Set-Cookie') | Select-Object -First 1).Split(';')[0]
        $location = [string]$bootstrap.Headers.Location
        $csrf = ([Uri]$location).Fragment.Substring('#csrf='.Length)
        $origin = "http://127.0.0.1:$($server.port)"
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$origin/api/status")
        $request.Headers.Add('Cookie', $cookie)
        $request.Headers.Add('X-MCAC-CSRF', $csrf)
        $request.Headers.Add('Origin', $origin)
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode -or $body -notmatch '"loopbackOnly":true') {
            throw "Authenticated HTML status failed: $body"
        }
        $index = $client.GetAsync("$origin/").GetAwaiter().GetResult()
        $html = $index.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $index.IsSuccessStatusCode -or $html -notmatch 'Minecraft AI Companion') {
            throw 'Embedded HTML frontend did not load'
        }
        Write-Output 'Authenticated API and embedded HTML passed.'
    }
    finally { $client.Dispose(); $handler.Dispose() }
}
finally {
    if (-not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        $process.WaitForExit(5000) | Out-Null
    }
    Remove-Item -LiteralPath $state -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $env:LOCALAPPDATA 'MinecraftAICompanion\html-terminal-current.json') -Force -ErrorAction SilentlyContinue
}

Write-Output 'HTML terminal first-start test passed from an arbitrary working directory.'
