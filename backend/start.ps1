param([switch]$ExcerptsOnly, [switch]$LocalOnly)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar = Join-Path $root 'target/folio-query-1.0.0.jar'
$runtime = Join-Path (Split-Path -Parent $root) '.local'
$llama = Join-Path $runtime 'llama/llama-server.exe'
$weights = Join-Path $runtime 'qwen2.5-1.5b-instruct-q4_k_m.gguf'
if (!(Test-Path -LiteralPath $weights)) {
    $weights = Join-Path $runtime 'qwen2.5-0.5b-instruct.gguf'
}
$modelPort = 8766

if (!(Test-Path -LiteralPath $jar)) {
    throw 'Build the API first with mvn -f backend/pom.xml package from the project root.'
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
$ownedModel = $null
$originalName = $env:FOLIO_MODEL_NAME
$originalUrl = $env:FOLIO_MODEL_URL
$originalGroqKey = $env:GROQ_API_KEY
try {
    if ($ExcerptsOnly -or $LocalOnly) {
        $env:GROQ_API_KEY = $null
    } elseif (!$env:GROQ_API_KEY) {
        $envFile = Join-Path $root '.env'
        if (Test-Path -LiteralPath $envFile -PathType Leaf) {
            try {
                foreach ($line in [IO.File]::ReadLines($envFile)) {
                    if ($line -match '^\s*GROQ_API_KEY\s*=\s*([A-Za-z0-9_-]+)\s*$') {
                        $env:GROQ_API_KEY = $Matches[1]
                    }
                }
            } catch {
                throw 'Unable to read backend/.env.'
            }
        }
    }
    if ($env:GROQ_API_KEY -and $env:GROQ_API_KEY -notmatch '^[A-Za-z0-9_-]+$') {
        throw 'GROQ_API_KEY must be a single unquoted value.'
    }

    if ($ExcerptsOnly) {
        $env:FOLIO_MODEL_NAME = ''
    } elseif (!$env:GROQ_API_KEY -and !$originalName -and (Test-Path -LiteralPath $llama) -and (Test-Path -LiteralPath $weights)) {
        $modelUrl = "http://127.0.0.1:$modelPort"
        try {
            $ownedModel = Start-Process -FilePath $llama -WindowStyle Hidden -PassThru -WorkingDirectory $runtime `
                -ArgumentList @('-m', ('"{0}"' -f $weights), '--alias', 'folio-qwen', '--host', '127.0.0.1',
                    '--port', $modelPort, '-c', '8192', '-np', '1', '--no-webui', '--cache-ram', '0', '--no-cache-prompt',
                    '--log-disable')
            $deadline = [DateTime]::UtcNow.AddSeconds(35)
            while (!$ownedModel.HasExited -and [DateTime]::UtcNow -lt $deadline) {
                try {
                    $listing = Invoke-RestMethod "$modelUrl/v1/models" -TimeoutSec 2
                    if ($listing.data.id -contains 'folio-qwen') {
                        $env:FOLIO_MODEL_NAME = 'folio-qwen'
                        $env:FOLIO_MODEL_URL = $modelUrl
                        break
                    }
                } catch {
                    Start-Sleep -Milliseconds 450
                }
            }
        } catch {
            Write-Warning 'The local model could not start.'
        }
        if (!$env:FOLIO_MODEL_NAME) {
            Write-Warning 'Folio will start with source excerpts.'
        }
    }
    $port = if ($env:PORT) { $env:PORT } else { '8765' }
    Write-Host "Folio is starting at http://127.0.0.1:$port"
    & $java '-Xmx768m' '-jar' $jar
    if ($LASTEXITCODE -ne 0) { throw "Java exited with code $LASTEXITCODE." }
} finally {
    if ($ownedModel -and !$ownedModel.HasExited) {
        Stop-Process -InputObject $ownedModel
    }
    $env:FOLIO_MODEL_NAME = $originalName
    $env:FOLIO_MODEL_URL = $originalUrl
    $env:GROQ_API_KEY = $originalGroqKey
}
