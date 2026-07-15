[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$Z,
    [int]$Port = 18767
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$ascii = [Text.Encoding]::ASCII

function Find-HeaderEnd([byte[]]$bytes) {
    for ($index = 0; $index -le $bytes.Length - 4; $index++) {
        if ($bytes[$index] -eq 13 -and $bytes[$index + 1] -eq 10 -and
            $bytes[$index + 2] -eq 13 -and $bytes[$index + 3] -eq 10) {
            return $index + 4
        }
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
        if ($read -le 0) { throw 'Replay provider client closed before request completion.' }
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
            return $utf8.GetString($bytes, $headerEnd, $contentLength)
        }
    }
}

$target = @{ dimension = 'minecraft:overworld'; x = $X; y = $Y; z = $Z }
$steps = @(
    @{
        goalState = 'reach verified chest'; capability = 'NavigateTo'; parameters = @{ target = $target }
        expectedResult = 'body reaches chest interaction range'; completionCriteria = @{ positionVerified = $true }
        failurePolicy = 'report and replan when unreachable'; opportunistic = $false; risk = 'LOW'
    },
    @{
        goalState = 'withdraw iron from chest'; capability = 'WithdrawFromStorage'
        parameters = @{ item = 'minecraft:iron_ingot'; quantity = 16; allowPartial = $false; target = $target }
        expectedResult = 'companion inventory and chest deltas agree'; completionCriteria = @{ inventoryDelta = 16 }
        failurePolicy = 'ask owner before expanding a shortage'; opportunistic = $false; risk = 'LOW'
    },
    @{
        goalState = 'return to owner'; capability = 'NavigateTo'; parameters = @{ target = 'owner' }
        expectedResult = 'body reaches owner'; completionCriteria = @{ ownerDistanceVerified = $true }
        failurePolicy = 'report and replan when unreachable'; opportunistic = $false; risk = 'LOW'
    },
    @{
        goalState = 'deliver iron to owner'; capability = 'DeliverItem'
        parameters = @{ item = 'minecraft:iron_ingot'; quantity = 16 }
        expectedResult = 'owner and companion inventory deltas agree'; completionCriteria = @{ ownerInventoryDelta = 16 }
        failurePolicy = 'retain items and report failed handoff'; opportunistic = $false; risk = 'LOW'
    }
)
$decision = @{
    kind = 'CREATE_PLAN'; understoodGoal = 'withdraw 16 iron from the verified chest and deliver it to the owner'
    constraints = @('ask the owner before expanding resource sources when the chest is short')
    assumptions = @(); steps = $steps; reply = 'I will use the verified chest and report any shortage first.'
    reason = 'REPLAY_FIXTURE_VERIFIED_CONTAINER_PLAN'
} | ConvertTo-Json -Compress -Depth 20
$envelope = @{ choices = @(@{ message = @{ content = $decision } }) } | ConvertTo-Json -Compress -Depth 20
$responseBody = $utf8.GetBytes($envelope)
$responseHeader = $ascii.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($responseBody.Length)`r`nConnection: close`r`n`r`n")

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-Output "Replay provider listening on 127.0.0.1:$Port target=$X,$Y,$Z"
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $null = Read-HttpRequest $stream
            $stream.Write($responseHeader, 0, $responseHeader.Length)
            $stream.Write($responseBody, 0, $responseBody.Length)
            $stream.Flush()
        } finally {
            $client.Dispose()
        }
    }
} finally {
    $listener.Stop()
}
