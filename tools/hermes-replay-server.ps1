[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$Z,
    [int]$Port = 18768
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$ascii = [Text.Encoding]::ASCII
$sessions = @{}
$nextSession = 0

function Find-HeaderEnd([byte[]]$bytes) {
    for ($index = 0; $index -le $bytes.Length - 4; $index++) {
        if ($bytes[$index] -eq 13 -and $bytes[$index + 1] -eq 10 -and
            $bytes[$index + 2] -eq 13 -and $bytes[$index + 3] -eq 10) { return $index + 4 }
    }
    return -1
}

function Read-HttpRequest([IO.Stream]$stream) {
    $memory = [IO.MemoryStream]::new()
    $buffer = [byte[]]::new(8192)
    $headerEnd = -1
    $contentLength = 0
    while ($true) {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) { throw 'Hermes replay client closed before request completion.' }
        $memory.Write($buffer, 0, $read)
        $bytes = $memory.ToArray()
        if ($headerEnd -lt 0) {
            $headerEnd = Find-HeaderEnd $bytes
            if ($headerEnd -ge 0) {
                $headers = $ascii.GetString($bytes, 0, $headerEnd)
                $match = [regex]::Match($headers, '(?im)^Content-Length:\s*(\d+)\s*$')
                if ($match.Success) { $contentLength = [int]$match.Groups[1].Value }
            }
        }
        if ($headerEnd -ge 0 -and $bytes.Length -ge $headerEnd + $contentLength) {
            $requestLine = ($headers -split "`r`n")[0]
            $path = ([regex]::Match($requestLine, '^\S+\s+([^\s?]+)')).Groups[1].Value
            return @{ path = $path; body = $utf8.GetString($bytes, $headerEnd, $contentLength) }
        }
    }
}

function Assert-TerminalObservation($request, [string]$expectedTool) {
    $results = @($request.toolResults)
    if ($results.Count -ne 1 -or $results[0].toolName -ne $expectedTool -or
        -not $results[0].terminal -or $results[0].observation.state -ne 'SUCCEEDED') {
        throw "Hermes replay expected terminal SUCCEEDED observation for $expectedTool."
    }
}

function Tool-Response([string]$callId, [string]$name, $arguments) {
    return @{ kind = 'TOOL_CALLS'; toolCalls = @(@{ callId = $callId; name = $name; arguments = $arguments }) }
}

function New-TurnResponse([string]$sessionId, $request) {
    $step = [int]$sessions[$sessionId]
    switch ($step) {
        0 { $response = @{
                kind = 'ASK_USER'
                question = @{
                    prompt = 'The verified chest contains only 6 of the requested 16 iron ingots. What should I do?'
                    reason = 'RESOURCE_SHORTAGE'
                    options = @(
                        @{ id = 'deliver_partial'; label = 'Deliver 6'; description = 'Return and deliver the verified stock.' },
                        @{ id = 'collect_missing'; label = 'Collect 10'; description = 'Acquire the missing amount first.' }
                    )
                    freeTextAllowed = $false
                    context = @{ available = 6; requested = 16 }
                }
            } }
        1 {
            $answer = $request.userMessage | ConvertFrom-Json
            if ($answer.type -ne 'user_answer' -or $answer.optionId -ne 'deliver_partial') {
                throw 'Hermes replay expected the persisted deliver_partial user answer.'
            }
            $response = Tool-Response 'navigate-chest-1' 'movement.navigate' @{
                dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z } }
        2 { Assert-TerminalObservation $request 'movement.navigate'; $response = Tool-Response 'withdraw-iron-1' 'inventory.withdraw' @{
                item = 'minecraft:iron_ingot'; quantity = 6; allowPartial = $false
                container = @{ dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z } } }
        3 { Assert-TerminalObservation $request 'inventory.withdraw'; $response = Tool-Response 'return-owner-1' 'movement.return' @{} }
        4 { Assert-TerminalObservation $request 'movement.return'; $response = Tool-Response 'deliver-iron-1' 'inventory.deliver' @{
                item = 'minecraft:iron_ingot'; quantity = 6; allowPartial = $false } }
        5 { Assert-TerminalObservation $request 'inventory.deliver'; $response = @{
                kind = 'FINAL_RESPONSE'; response = 'I reached the chest, withdrew six iron ingots, returned, and delivered all six.' } }
        default { throw "Hermes replay session advanced past its terminal turn: $sessionId" }
    }
    $sessions[$sessionId] = $step + 1
    return $response
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-Output "Hermes replay listening on 127.0.0.1:$Port target=$X,$Y,$Z"
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $http = Read-HttpRequest $stream
            $request = if ($http.body) { $http.body | ConvertFrom-Json } else { $null }
            if ($http.path -eq '/sessions') {
                $nextSession++
                $sessionId = 'replay{0:d8}' -f $nextSession
                $sessions[$sessionId] = 0
                $response = @{ sessionId = $sessionId }
            } elseif ($http.path -match '^/sessions/([A-Za-z0-9_-]+)/turns$') {
                $sessionId = $Matches[1]
                if (-not $sessions.ContainsKey($sessionId)) { throw "Unknown replay session: $sessionId" }
                $response = New-TurnResponse $sessionId $request
            } elseif ($http.path -match '^/sessions/([A-Za-z0-9_-]+)/resume$') {
                $sessionId = $Matches[1]
                $response = @{ resumed = $sessions.ContainsKey($sessionId) }
            } elseif ($http.path -match '^/sessions/([A-Za-z0-9_-]+)/cancel$') {
                $sessions.Remove($Matches[1]); $response = @{ cancelled = $true }
            } else { throw "Unsupported Hermes replay path: $($http.path)" }
            $body = $utf8.GetBytes(($response | ConvertTo-Json -Compress -Depth 20))
            $header = $ascii.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($header, 0, $header.Length); $stream.Write($body, 0, $body.Length); $stream.Flush()
        } catch {
            Write-Warning "Hermes replay connection failed: $($_.Exception.Message)"
        } finally { $client.Dispose() }
    }
} finally { $listener.Stop() }
