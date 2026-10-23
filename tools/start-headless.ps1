<# Entrada stdio para clientes MCP. Diagnóstico sólo en stderr. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Workspace,
    [string]$RuntimeHome = (Join-Path (Split-Path $PSScriptRoot) 'build\runtime\Archi'),
    [string]$StateHome
)
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
if (-not $StateHome) { $StateHome = Join-Path $Workspace '.axioma\runtime' }
$StateHome = [IO.Path]::GetFullPath($StateHome)
$profile = Join-Path $StateHome ([guid]::NewGuid().ToString('N'))
$configuration = Join-Path $profile 'configuration'
$simple = Join-Path $configuration 'org.eclipse.equinox.simpleconfigurator'
New-Item -ItemType Directory -Force -Path $simple | Out-Null
# Cada proceso mantiene su propio caché Equinox; comparte sólo bundles de lectura.
Copy-Item (Join-Path $RuntimeHome 'configuration\config.ini') $configuration
Copy-Item (Join-Path $RuntimeHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info') $simple
$launcher = @(Get-ChildItem (Join-Path $RuntimeHome 'plugins') -Filter 'org.eclipse.equinox.launcher_*.jar')[0].FullName
# Estado de instancia separado del directorio de instalación compartido.
& (Join-Path $RuntimeHome 'jre\bin\java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Djava.awt.headless=true' "-Daxioma.workspace=$Workspace" `
    "-Duser.home=$profile" "-Dosgi.instance.area=$profile\instancia" "-Ddata.location=$profile\datos" `
    -jar $launcher -install $RuntimeHome -configuration $configuration `
    -application io.github.h0w4r.axioma.headless.server -nosplash -clean
exit $LASTEXITCODE
