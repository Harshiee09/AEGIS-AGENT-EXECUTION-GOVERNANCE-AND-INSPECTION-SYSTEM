param([int]$Port = 3307)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$client = Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.4\bin\mysqladmin.exe'
$secret = Read-Host 'AEGIS-only MySQL password' -AsSecureString
$env:MYSQL_PWD = [System.Net.NetworkCredential]::new('', $secret).Password
try {
    & $client --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root shutdown
    if ($LASTEXITCODE -ne 0) { throw "Could not stop AEGIS-only MySQL on port $Port." }
    $pidFile = Join-Path $project ".local-mysql\$Port\server.pid"
    Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
    Write-Host "AEGIS-only MySQL stopped on port $Port."
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    $secret.Dispose()
}
