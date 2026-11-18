param([int]$Port = 3307)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$instance = Join-Path $project ".local-mysql\$Port"
$data = Join-Path $instance 'data'
$base = Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.4'
$server = Join-Path $base 'bin\mysqld.exe'
$client = Join-Path $base 'bin\mysql.exe'
if (-not (Test-Path -LiteralPath $server)) { throw "MySQL Server 8.4 was not found at $server" }
$fresh = -not (Test-Path -LiteralPath (Join-Path $data 'mysql'))
if ($fresh) {
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    & $server --no-defaults --initialize-insecure --console "--basedir=$base" "--datadir=$data"
    if ($LASTEXITCODE -ne 0) { throw 'Local MySQL initialization failed.' }
}
& (Join-Path $PSScriptRoot 'start-local-db.ps1') -Port $Port
$secret = Read-Host $(if ($fresh) { 'Choose a new AEGIS-only MySQL password' } else { 'AEGIS-only MySQL password' }) -AsSecureString
$password = [System.Net.NetworkCredential]::new('', $secret).Password
if ($password.Length -lt 8) { throw 'Use a password with at least 8 characters.' }
try {
    if ($fresh) {
        $escaped = $password.Replace("'", "''")
        "ALTER USER 'root'@'localhost' IDENTIFIED BY '$escaped';" |
            & $client --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root
        if ($LASTEXITCODE -ne 0) { throw 'Could not set the AEGIS-only MySQL password.' }
    }
    $env:MYSQL_PWD = $password
    Get-Content -LiteralPath (Join-Path $project 'sql\schema.sql') -Raw |
        & $client --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the AEGIS schema.' }
    Write-Host "AEGIS schema is ready on 127.0.0.1:$Port."
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    $password = $null
    $secret.Dispose()
}
