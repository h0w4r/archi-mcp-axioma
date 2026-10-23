<# Controlador de aceptación del workbench real con datos y configuración aislados. #>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Model, [Parameter(Mandatory)][string]$ActorId,
    [Parameter(Mandatory)][string]$Evidence,
    [string]$OtherModel = '', [string]$OtherActorId = '',
    [string]$PluginPackage = '',
    [string]$RuntimeHome = (Join-Path (Split-Path $PSScriptRoot) 'build/runtime/Archi'))
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Console]::OutputEncoding
$profile=Join-Path $Evidence 'perfil-gui'
$config=Join-Path $profile 'configuration'
$simple=Join-Path $config 'org.eclipse.equinox.simpleconfigurator'
New-Item -ItemType Directory -Force -Path $simple | Out-Null
Copy-Item (Join-Path $RuntimeHome 'configuration/config.ini') $config
Copy-Item (Join-Path $RuntimeHome 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info') $simple
$launcher=@(Get-ChildItem (Join-Path $RuntimeHome 'plugins') -Filter 'org.eclipse.equinox.launcher_*.jar')[0].FullName
& (Join-Path $RuntimeHome 'jre/bin/java.exe') '-Dfile.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dstdout.encoding=UTF-8' `
    "-Duser.home=$profile" "-Ddata.location=$profile/datos" "-Dosgi.instance.area=$profile/instancia" `
    "-Daxioma.verify.evidence=$Evidence" "-Daxioma.verify.model=$Model" "-Daxioma.verify.actor=$ActorId" `
    "-Daxioma.verify.otherModel=$OtherModel" "-Daxioma.verify.otherActor=$OtherActorId" `
    "-Daxioma.verify.package=$PluginPackage" `
    -jar $launcher -install $RuntimeHome -configuration $config -application io.github.h0w4r.axioma.live.verify -nosplash -clean
exit $LASTEXITCODE
