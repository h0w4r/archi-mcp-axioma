<# Prepara el adaptador sólo en el runtime aislado que ya usa la aceptación headless. #>
[CmdletBinding()]
param([string]$RuntimeHome = (Join-Path (Split-Path $PSScriptRoot) 'build/runtime/Archi'), [string]$BundlePath)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if(-not (Test-Path (Join-Path $RuntimeHome '.axioma-runtime.json'))){throw 'Se requiere un runtime Axioma aislado, no la instalación del operador.'}
if($BundlePath){
    $BundlePath=(Resolve-Path -LiteralPath $BundlePath).Path
    $manifest=Get-Content (Join-Path $BundlePath 'META-INF/MANIFEST.MF') -Raw
    $version=[regex]::Match($manifest,'(?m)^Bundle-Version:\s*(\S+)').Groups[1].Value
    if(-not $version -or $manifest -notmatch 'Bundle-SymbolicName: io.github.h0w4r.axioma.live;'){throw 'No es un bundle Live de Axioma.'}
    $latest=[pscustomobject]@{bundle=$BundlePath; version=$version}
} else { $latest=Get-Content (Join-Path $root 'build/live-latest.json') -Raw | ConvertFrom-Json }
$info=Join-Path $RuntimeHome 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
$lines=@(Get-Content $info | Where-Object {$_ -notmatch '^io\.github\.h0w4r\.axioma\.live,'})
$uri=[Uri]::new($latest.bundle + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
$lines += "io.github.h0w4r.axioma.live,$($latest.version),$uri,4,false"
[IO.File]::WriteAllLines($info,$lines,[Text.UTF8Encoding]::new($false))
@{runtime=$RuntimeHome; bundle=$latest.bundle} | ConvertTo-Json
