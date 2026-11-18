$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$lib = Join-Path $project 'lib'
$jar = Join-Path $lib 'mysql-connector-j-9.7.0.jar'
New-Item -ItemType Directory -Path $lib -Force | Out-Null
if (Test-Path -LiteralPath $jar) {
    Write-Host "Connector/J already present: $jar"
    exit 0
}
$url = 'https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'
Write-Host 'Downloading MySQL Connector/J 9.7.0 from Maven Central...'
Invoke-WebRequest -Uri $url -OutFile $jar
if ((Get-Item -LiteralPath $jar).Length -lt 1000000) {
    Remove-Item -LiteralPath $jar
    throw 'The downloaded Connector/J file is incomplete.'
}
Write-Host "Saved: $jar"
