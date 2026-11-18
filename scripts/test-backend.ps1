param([int]$DbPort = 3309, [int]$HttpPort = 8080)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$instance = Join-Path $project ".local-mysql\$DbPort"
$agentToken = Get-Content -LiteralPath (Join-Path $instance 'api-token.txt') -Raw
$adminToken = Get-Content -LiteralPath (Join-Path $instance 'admin-token.txt') -Raw
function Call-Api([string]$method, [string]$path, $body, [bool]$admin = $false) {
    $token = if ($admin) { $adminToken } else { $agentToken }
    $params = @{ Uri = "http://127.0.0.1:$HttpPort$path"; Method = $method;
        Headers = @{ Authorization = "Bearer $token" }; TimeoutSec = 20 }
    if ($null -ne $body) {
        $params.ContentType = 'application/json'
        $params.Body = ConvertTo-Json -InputObject $body -Compress -Depth 6
    }
    return Invoke-RestMethod @params
}
function Submit($action, $path, $content = $null, $destination = $null) {
    $body = @{ action = $action; path = $path }
    if ($null -ne $content) { $body.content = $content }
    if ($null -ne $destination) { $body.destination = $destination }
    return Call-Api 'POST' '/api/operations' $body
}
function Approve($response) {
    return Call-Api 'POST' "/api/operations/$($response.operation.id)/approve" @{} $true
}
function Assert($condition, $message) { if (-not $condition) { throw $message } }
function Expect-HttpStatus([string]$method, [string]$path, [string]$body, [int]$status, [bool]$admin = $false) {
    try {
        $token = if ($admin) { $adminToken } else { $agentToken }
        $params = @{ Uri = "http://127.0.0.1:$HttpPort$path"; Method = $method; UseBasicParsing = $true;
            Headers = @{ Authorization = "Bearer $token" }; ContentType = 'application/json'; Body = $body }
        Invoke-WebRequest @params | Out-Null
        throw "Expected HTTP $status for $path"
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $status) { throw }
    }
}
$folder = 'integration-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$file = "$folder/a.txt"
$copy = "$folder/b.txt"
$moved = "$folder/c.txt"
$root = Join-Path $project 'workspace-files'
$mkdir = Submit 'MKDIR' $folder
Assert ($mkdir.operation.status -eq 'PENDING') 'MKDIR should await approval'
Assert (-not (Test-Path -LiteralPath (Join-Path $root $folder))) 'MKDIR ran before approval'
Assert ((Approve $mkdir).operation.status -eq 'EXECUTED') 'MKDIR failed'
$create = Submit 'CREATE' $file 'one'
Assert ((Approve $create).operation.status -eq 'EXECUTED') 'CREATE failed'
Expect-HttpStatus 'POST' "/api/operations/$($create.operation.id)/approve" '{}' 409 $true
$read = Submit 'READ' $file
Assert ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($read.output.contentBase64)) -eq 'one') 'READ failed'
$write = Submit 'WRITE' $file 'two'
Assert ((Approve $write).operation.status -eq 'EXECUTED') 'WRITE failed'
$append = Submit 'APPEND' $file '!'
Assert ((Approve $append).operation.status -eq 'EXECUTED') 'APPEND failed'
$copyRequest = Submit 'COPY' $file $null $copy
Assert ((Approve $copyRequest).operation.status -eq 'EXECUTED') 'COPY failed'
$moveRequest = Submit 'MOVE' $copy $null $moved
Assert ((Approve $moveRequest).operation.status -eq 'EXECUTED') 'MOVE failed'
$stat = Submit 'STAT' $moved
Assert ($stat.output.size -eq 4) 'STAT failed'
$list = Submit 'LIST' $folder
Assert (($list.output.entries.name -contains 'a.txt') -and ($list.output.entries.name -contains 'c.txt')) 'LIST failed'
$blocked = Submit 'CREATE' "$folder/blocked.txt" 'no'
Expect-HttpStatus 'POST' "/api/operations/$($blocked.operation.id)/approve" '{}' 403
$blockResult = Call-Api 'POST' "/api/operations/$($blocked.operation.id)/block" @{} $true
Assert ($blockResult.operation.status -eq 'BLOCKED') 'BLOCK failed'
Assert (-not (Test-Path -LiteralPath (Join-Path $root "$folder/blocked.txt"))) 'BLOCKED file was written'
$delete1 = Submit 'DELETE' $file
$delete2 = Submit 'DELETE' $moved
Assert ((Approve $delete1).operation.status -eq 'EXECUTED') 'DELETE a failed'
Assert ((Approve $delete2).operation.status -eq 'EXECUTED') 'DELETE c failed'
$deleteFolder = Submit 'DELETE' $folder
Assert ((Approve $deleteFolder).operation.status -eq 'EXECUTED') 'DELETE directory failed'
Assert (-not (Test-Path -LiteralPath (Join-Path $root $folder))) 'Directory was not removed'
Expect-HttpStatus 'POST' '/api/operations' '{"action":"READ","path":"../README.md"}' 400
$events = Call-Api 'GET' '/api/events?since=0' $null
$blockEvents = @($events.events | Where-Object { $_.requestId -eq $blocked.operation.id -and $_.type -eq 'BLOCKED' })
Assert ($blockEvents.Count -eq 1) 'Audit feed missing block event'
Write-Host 'PASS: all 10 file actions, approval, blocking and audit feed.'
