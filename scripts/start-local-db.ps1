param([int]$Port = 3307)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$instance = Join-Path $project ".local-mysql\$Port"
$data = Join-Path $instance 'data'
$pidFile = Join-Path $instance 'server.pid'
$server = Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.4\bin\mysqld.exe'
if (-not (Test-Path -LiteralPath $server)) { throw "MySQL Server 8.4 was not found at $server" }
if (-not (Test-Path -LiteralPath (Join-Path $data 'mysql'))) {
    throw "Local database is not initialized. Run .\scripts\setup-local-db.ps1 -Port $Port first."
}
function Test-PortOpen {
    try {
        $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $Port)
        $client.Dispose()
        return $true
    } catch { return $false }
}
if (Test-PortOpen) {
    if (Test-Path -LiteralPath $pidFile) {
        $storedPid = [int](Get-Content -LiteralPath $pidFile -Raw)
        $running = Get-Process -Id $storedPid -ErrorAction SilentlyContinue
        if ($running -and $running.ProcessName -eq 'mysqld') {
            Write-Host "AEGIS-only MySQL is already listening on port $Port."
            exit 0
        }
    }
    throw "Port $Port is already in use by another process. Choose a different -Port value."
}
$base = Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.4'
$log = Join-Path $instance 'mysql.log'
$arguments = @('--no-defaults', "--basedir=`"$base`"", "--datadir=`"$data`"",
    "--port=$Port", '--bind-address=127.0.0.1', '--mysqlx=OFF', "--log-error=`"$log`"")
$process = Start-Process -FilePath $server -ArgumentList $arguments -WindowStyle Hidden -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    if (Test-PortOpen) {
        Write-Host "AEGIS-only MySQL is ready on 127.0.0.1:$Port."
        exit 0
    }
    Start-Sleep -Milliseconds 500
}
Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
throw "AEGIS-only MySQL did not start. Check $log"
