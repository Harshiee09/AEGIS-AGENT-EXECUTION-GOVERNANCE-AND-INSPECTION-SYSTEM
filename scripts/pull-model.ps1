param([string[]]$Models = @('gemma4:latest', 'nemotron-3-nano:4b'))
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:11434'
try { $installed = @((Invoke-RestMethod -Uri "$base/api/tags" -TimeoutSec 10).models | ForEach-Object name) }
catch { throw 'Local Ollama is not running on 127.0.0.1:11434.' }
foreach ($model in $Models) {
    if ($model -notmatch '^[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9._-]+)?$') { throw "Invalid model name: $model" }
    if ($installed -contains $model) {
        Write-Host "$model is already installed."
        continue
    }
    Write-Host "Pulling $model from Ollama..."
    $body = @{ name = $model; stream = $false } | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Uri "$base/api/pull" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 3600
    if ($result.status -ne 'success') { throw "Ollama did not confirm a successful pull for $model." }
    Write-Host "$model is ready."
}
