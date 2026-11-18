param([int]$Port = 3309)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$instance = Join-Path $project ".local-mysql\$Port"
$data = Join-Path $instance 'data'
$secretFile = Join-Path $instance 'root-password.txt'
$base = Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.4'
$server = Join-Path $base 'bin\mysqld.exe'
$client = Join-Path $base 'bin\mysql.exe'
if (-not (Test-Path -LiteralPath $server) -or -not (Test-Path -LiteralPath $client)) {
    throw 'MySQL Server 8.4 is required. See README.md.'
}
$fresh = -not (Test-Path -LiteralPath (Join-Path $data 'mysql'))
if ($fresh) {
    if (Test-Path -LiteralPath $secretFile) { throw 'Credential exists without a database. Inspect the local instance.' }
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    & $server --no-defaults --initialize-insecure --console "--basedir=$base" "--datadir=$data"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed.' }
}
& (Join-Path $PSScriptRoot 'start-local-db.ps1') -Port $Port
if (-not $fresh -and -not (Test-Path -LiteralPath $secretFile)) {
    # Recover an initialization interrupted before the password was set.
    $probe = & $client --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root --batch --skip-column-names -e 'SELECT 1' 2>$null
    if ($LASTEXITCODE -ne 0 -or $probe -ne '1') { throw "No saved credential for port $Port. Existing instances are not modified." }
    $fresh = $true
}
if ($fresh) {
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 36
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $password = [Convert]::ToBase64String($bytes)
    $sqlPassword = $password.Replace("'", "''")
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '$sqlPassword';" |
        & $client --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root
    if ($LASTEXITCODE -ne 0) { throw 'Could not secure the new MySQL instance.' }
    Set-Content -LiteralPath $secretFile -Value $password -NoNewline
} else {
    $password = Get-Content -LiteralPath $secretFile -Raw
}
try {
    $env:MYSQL_PWD = $password
    Get-Content -LiteralPath (Join-Path $project 'sql\schema.sql') -Raw |
        & $client --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize the AEGIS schema.' }
    Write-Host "AEGIS MySQL schema is ready on 127.0.0.1:$Port."
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    $password = $null
}
