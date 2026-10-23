<# Empaqueta el bundle y los launchers; no incluye modelos ni una instalación de Archi. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$latest = Get-Content (Join-Path $root 'build\headless-latest.json') -Raw | ConvertFrom-Json
$stage = Join-Path $root ('build\distribution\' + $latest.version)
if (Test-Path -LiteralPath $stage) { throw 'La distribución ya existe; conservarla antes de volver a empaquetar.' }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'tools'), (Join-Path $stage 'headless') | Out-Null
Copy-Item -LiteralPath $latest.bundle -Destination (Join-Path $stage 'bundle') -Recurse
foreach ($name in @('start-headless.ps1', 'prepare-headless.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $stage 'tools')
}
Copy-Item (Join-Path $root 'headless\PAQUETE.md') (Join-Path $stage 'README.md')
Copy-Item (Join-Path $root 'LICENSE') $stage
Copy-Item (Join-Path $root 'headless\DEPENDENCIAS.md') (Join-Path $stage 'headless')
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$archive = Join-Path $dist ('archi-mcp-axioma-headless-' + $latest.version + '.zip')
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $archive)
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Path, Hash | ConvertTo-Json
