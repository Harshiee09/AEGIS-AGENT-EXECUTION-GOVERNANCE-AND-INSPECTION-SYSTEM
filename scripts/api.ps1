param(
    [ValidateSet('GET','POST')][string]$Method = 'GET',
    [string]$Path = '/health',
    [string]$Body = '',
    [switch]$Admin,
    [int]$DbPort = 3309,
    [int]$HttpPort = 8080
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$instance = Join-Path $project ".local-mysql\$DbPort"
$tokenFile = Join-Path $instance $(if ($Admin) { 'admin-token.txt' } else { 'api-token.txt' })
if (-not (Test-Path -LiteralPath $tokenFile)) { throw 'Start scripts/run-backend.ps1 first.' }
$token = Get-Content -LiteralPath $tokenFile -Raw
if (-not $Path.StartsWith('/api/')) { throw 'Path must start with /api/.' }
$uri = "http://127.0.0.1:$HttpPort$Path"
$parameters = @{ Uri = $uri; Method = $Method; Headers = @{ Authorization = "Bearer $token" }; TimeoutSec = 130 }
if ($Method -eq 'POST') {
    $parameters.ContentType = 'application/json'
    $parameters.Body = $(if ($Body) { $Body } else { '{}' })
}
Invoke-RestMethod @parameters | ConvertTo-Json -Depth 20
