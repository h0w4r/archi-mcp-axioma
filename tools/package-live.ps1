<# Empaqueta el adaptador opcional en el formato reconocido por el instalador de Archi. #>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$latest=Get-Content (Join-Path $root 'build/live-latest.json') -Raw | ConvertFrom-Json
$stage=Join-Path $root ('build/live-distribution/' + $latest.version)
if(Test-Path -LiteralPath $stage){throw 'El paquete ya existe; conservarlo antes de volver a empaquetar.'}
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$bundleName='io.github.h0w4r.axioma.live_' + $latest.version
Copy-Item -LiteralPath $latest.bundle -Destination (Join-Path $stage $bundleName) -Recurse
Copy-Item (Join-Path $root 'LICENSE') (Join-Path $stage $bundleName)
Copy-Item (Join-Path $root 'live/README.md') (Join-Path $stage $bundleName)
# Firma literal del formato, igual a la utilizada por el instalador nativo.
[IO.File]::WriteAllText((Join-Path $stage 'archi-plugin'),'Magic file to signify this is an Archi plug-in bundle.',[Text.UTF8Encoding]::new($false))
$dist=Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$output=Join-Path $dist ('archi-mcp-axioma-live-' + $latest.version + '.archiplugin')
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$output)
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Path,Hash | ConvertTo-Json
