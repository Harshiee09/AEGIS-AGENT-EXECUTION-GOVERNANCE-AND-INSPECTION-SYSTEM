param([switch]$SmokeTest)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jar = Join-Path $project 'lib\mysql-connector-j-9.7.0.jar'
$build = Join-Path $project 'build'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Connector/J is missing. Run .\scripts\setup-driver.ps1 first.'
}
New-Item -ItemType Directory -Path $build -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'No Java source files were found.' }
$compiler = Get-Command javac -All | Where-Object { $_.Source -notlike 'C:\Program Files\Common Files\Oracle\Java\javapath\*' } | Select-Object -First 1 -ExpandProperty Source
if (-not $compiler) { throw 'JDK 17 javac is not available on PATH.' }
& $compiler --release 17 -encoding UTF-8 -d $build $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$mainClass = if ($SmokeTest) { 'aegis.SmokeTest' } else { 'aegis.Main' }
& java -cp "$build;$jar" $mainClass
if ($LASTEXITCODE -ne 0) { throw "$mainClass exited with an error." }
