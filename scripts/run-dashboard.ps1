param([int]$DbPort = 3309)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'setup-backend-db.ps1') -Port $DbPort
$passwordFile = Join-Path $project ".local-mysql\$DbPort\root-password.txt"
$oldUrl = $env:AEGIS_DB_URL
$oldUser = $env:AEGIS_DB_USER
$oldPassword = $env:AEGIS_DB_PASSWORD
$env:AEGIS_DB_URL = "jdbc:mysql://127.0.0.1:$DbPort/aegis"
$env:AEGIS_DB_USER = 'root'
$env:AEGIS_DB_PASSWORD = Get-Content -LiteralPath $passwordFile -Raw
try { & (Join-Path $PSScriptRoot 'run.ps1') }
finally {
    $env:AEGIS_DB_URL = $oldUrl
    $env:AEGIS_DB_USER = $oldUser
    $env:AEGIS_DB_PASSWORD = $oldPassword
}
