param([int]$Port = 3307, [switch]$SmokeTest)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'start-local-db.ps1') -Port $Port
$oldUrl = $env:AEGIS_DB_URL
$oldPassword = $env:AEGIS_DB_PASSWORD
$env:AEGIS_DB_URL = "jdbc:mysql://127.0.0.1:$Port/aegis"
if (-not $env:AEGIS_DB_PASSWORD) {
    $secret = Read-Host 'AEGIS-only MySQL password' -AsSecureString
    $env:AEGIS_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
    $secret.Dispose()
}
try {
    & (Join-Path $PSScriptRoot 'run.ps1') -SmokeTest:$SmokeTest
} finally {
    $env:AEGIS_DB_URL = $oldUrl
    $env:AEGIS_DB_PASSWORD = $oldPassword
}
