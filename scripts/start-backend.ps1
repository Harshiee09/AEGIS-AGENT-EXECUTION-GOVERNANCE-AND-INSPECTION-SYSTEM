param([int]$Port = 3309, [int]$HttpPort = 8080, [string]$FileRoot = '')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$uri = "http://127.0.0.1:$HttpPort"
try { $health = Invoke-RestMethod -Uri "$uri/health" -TimeoutSec 2 }
catch { $health = $null }
if ($health -and $health.service -eq 'aegis' -and $health.status -eq 'ok') {
    Write-Host "AEGIS is already running at $uri/"
    exit 0
}
$runtime = Join-Path $project 'build\runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$stdout = Join-Path $runtime 'backend.log'
$stderr = Join-Path $runtime 'backend-error.log'
$script = Join-Path $PSScriptRoot 'run-backend.ps1'
$arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$script`"", '-Port', $Port, '-HttpPort', $HttpPort)
if ($FileRoot) {
    if ($FileRoot.Contains('"')) { throw 'FileRoot cannot contain a quote.' }
    $arguments += @('-FileRoot', "`"$FileRoot`"")
}
$process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Set-Content -LiteralPath (Join-Path $runtime 'launcher.pid') -Value $process.Id
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri "$uri/health" -TimeoutSec 1
        if ($health.service -eq 'aegis' -and $health.status -eq 'ok') {
            Set-Content -LiteralPath (Join-Path $runtime 'backend.pid') -Value $health.pid
            Write-Host "AEGIS is running at $uri/"
            Write-Host "Logs: $runtime"
            exit 0
        }
    } catch { }
    if ($process.HasExited) { throw "AEGIS exited during startup. See $stderr and $stdout." }
    Start-Sleep -Milliseconds 1000
}
throw "AEGIS did not become ready. See $stderr and $stdout."
