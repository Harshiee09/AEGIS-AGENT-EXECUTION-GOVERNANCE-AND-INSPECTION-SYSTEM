param([int]$DbPort = 3309, [int]$HttpPort = 8080)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tokenFile = Join-Path $project ".local-mysql\$DbPort\api-token.txt"
if (-not (Test-Path -LiteralPath $tokenFile)) { throw 'Start scripts/start-backend.ps1 first.' }
$env:AEGIS_API_TOKEN = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
$env:AEGIS_API_URL = "http://127.0.0.1:$HttpPort"
$jar = Join-Path $project 'lib\mysql-connector-j-9.7.0.jar'
$build = Join-Path $project 'build'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$compiler = Get-Command javac -All | Where-Object { $_.Source -notlike 'C:\Program Files\Common Files\Oracle\Java\javapath\*' } | Select-Object -First 1 -ExpandProperty Source
if (-not $compiler) { throw 'JDK 17 javac is not available on PATH.' }
& $compiler --release 17 -encoding UTF-8 -cp "$build;$jar" -d $build $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
try { & java -cp "$build;$jar" aegis.McpServer }
finally { Remove-Item Env:AEGIS_API_TOKEN,Env:AEGIS_API_URL -ErrorAction SilentlyContinue }
