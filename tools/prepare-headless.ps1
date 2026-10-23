<# Prepara un runtime aislado; nunca instala bundles en el Archi del operador. #>
[CmdletBinding()]
param([string]$ArchiHome = 'C:\Program Files\Archi', [string]$RuntimeHome, [string]$BundlePath)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if (-not $RuntimeHome) { $RuntimeHome = Join-Path $root 'build\runtime\Archi' }
$RuntimeHome = [IO.Path]::GetFullPath($RuntimeHome)
$ArchiHome = (Resolve-Path -LiteralPath $ArchiHome).Path
if ($RuntimeHome.TrimEnd('\', '/') -eq $ArchiHome.TrimEnd('\', '/') -or
    $RuntimeHome.StartsWith($ArchiHome.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'El runtime debe estar separado de la instalación de Archi.'
}
if ($BundlePath) {
    $BundlePath = (Resolve-Path -LiteralPath $BundlePath).Path
    $manifest = Get-Content (Join-Path $BundlePath 'META-INF\MANIFEST.MF') -Raw
    $version = [regex]::Match($manifest, '(?m)^Bundle-Version:\s*(\S+)').Groups[1].Value
    if (-not $version -or $manifest -notmatch 'Bundle-SymbolicName: io.github.h0w4r.axioma.headless;') { throw 'No es un bundle headless de Axioma válido.' }
    $latest = [pscustomobject]@{ bundle = $BundlePath; version = $version }
} else { $latest = Get-Content (Join-Path $root 'build\headless-latest.json') -Raw | ConvertFrom-Json }
$marker = Join-Path $RuntimeHome '.axioma-runtime.json'
if ((Test-Path (Join-Path $RuntimeHome 'plugins')) -and -not (Test-Path -LiteralPath $marker)) {
    throw 'El destino ya contiene una instalación que no fue preparada como runtime Axioma. Usar otro directorio.'
}
if (-not (Test-Path (Join-Path $RuntimeHome 'plugins'))) {
    Write-Output 'Preparando copia aislada de la distribución de Archi...'
    New-Item -ItemType Directory -Force -Path $RuntimeHome | Out-Null
    Copy-Item (Join-Path $ArchiHome '*') $RuntimeHome -Recurse
    [IO.File]::WriteAllText($marker, (@{ source = $ArchiHome; preparedAt = (Get-Date).ToString('o') } | ConvertTo-Json))
}
$info = Join-Path $RuntimeHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
$lines = @(Get-Content $info | Where-Object { $_ -notmatch '^io\.github\.h0w4r\.axioma\.headless,' })
$bundleUri = [Uri]::new($latest.bundle + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
$lines += "io.github.h0w4r.axioma.headless,$($latest.version),$bundleUri,4,false"
[IO.File]::WriteAllLines($info, $lines, [Text.UTF8Encoding]::new($false))
@{ runtime = $RuntimeHome; bundle = $latest.bundle } | ConvertTo-Json
