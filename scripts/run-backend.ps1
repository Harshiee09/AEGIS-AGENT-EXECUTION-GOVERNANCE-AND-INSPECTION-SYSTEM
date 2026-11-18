param([int]$Port = 3309, [int]$HttpPort = 8080, [string]$FileRoot = '')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'setup-backend-db.ps1') -Port $Port
$instance = Join-Path $project ".local-mysql\$Port"
$passwordFile = Join-Path $instance 'root-password.txt'
function Read-Secret([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw
}
function Ensure-Token([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        $bytes = New-Object byte[] 36
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        $token = [Convert]::ToBase64String($bytes)
        Set-Content -LiteralPath $Path -Value $token -NoNewline
    }
    return Read-Secret $Path
}
$apiToken = Ensure-Token (Join-Path $instance 'api-token.txt')
$adminToken = Ensure-Token (Join-Path $instance 'admin-token.txt')
$jar = Join-Path $project 'lib\mysql-connector-j-9.7.0.jar'
$build = Join-Path $project 'build'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Connector/J is missing. Run scripts/setup-driver.ps1.' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$compiler = Get-Command javac -All | Where-Object { $_.Source -notlike 'C:\Program Files\Common Files\Oracle\Java\javapath\*' } | Select-Object -First 1 -ExpandProperty Source
if (-not $compiler) { throw 'JDK 17 javac is not available on PATH.' }
& $compiler --release 17 -encoding UTF-8 -d $build $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
if (-not $FileRoot) { $FileRoot = Join-Path $project 'workspace-files' }
New-Item -ItemType Directory -Path $FileRoot -Force | Out-Null
$env:AEGIS_DB_URL = "jdbc:mysql://127.0.0.1:$Port/aegis"
$env:AEGIS_DB_USER = 'root'
$env:AEGIS_DB_PASSWORD = Read-Secret $passwordFile
$env:AEGIS_API_TOKEN = $apiToken
$env:AEGIS_ADMIN_TOKEN = $adminToken
$env:AEGIS_AGENT_NAME = 'AEGIS AI Agent'
$env:AEGIS_FILE_ROOT = (Resolve-Path -LiteralPath $FileRoot).Path
$env:AEGIS_PORT = [string]$HttpPort
try {
    & java -cp "$build;$jar" aegis.BackendMain
    if ($LASTEXITCODE -ne 0) { throw "AEGIS backend exited with code $LASTEXITCODE." }
}
finally {
    Remove-Item Env:AEGIS_DB_PASSWORD,Env:AEGIS_API_TOKEN,Env:AEGIS_ADMIN_TOKEN,Env:AEGIS_AGENT_NAME -ErrorAction SilentlyContinue
}
