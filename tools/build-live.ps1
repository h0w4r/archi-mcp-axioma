<# Compila el adaptador opcional contra las bibliotecas oficiales de Archi. #>
[CmdletBinding()]
param([string]$ArchiHome = 'C:\Program Files\Archi', [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$build = Join-Path $root ('build\live\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$bundle = Join-Path $build 'io.github.h0w4r.axioma.live'
New-Item -ItemType Directory -Force -Path $bundle | Out-Null
$jars = @(Get-ChildItem (Join-Path $ArchiHome 'plugins') -Recurse -Filter '*.jar' -File)
$utf8 = [Text.UTF8Encoding]::new($false)
$lines = @('--release', '21', '-encoding', 'UTF-8', '-classpath', ('"' + (($jars.FullName -join ';') -replace '\\','/') + '"'),
    '-d', ('"' + ($bundle -replace '\\','/') + '"'))
$lines += Get-ChildItem (Join-Path $root 'live/src') -Recurse -Filter '*.java' | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' }
$argsFile = Join-Path $build 'javac.args'
[IO.File]::WriteAllLines($argsFile,$lines,$utf8)
& (Join-Path $JavaHome 'bin/javac.exe') "@$argsFile"
if($LASTEXITCODE){throw 'Falló la compilación del adaptador en vivo.'}
Copy-Item (Join-Path $root 'live/META-INF') $bundle -Recurse
Copy-Item (Join-Path $root 'live/plugin.xml') $bundle
$version = '0.1.0.local' + (Get-Date -Format 'yyyyMMddHHmmssfff')
$manifest = Join-Path $bundle 'META-INF/MANIFEST.MF'
[IO.File]::WriteAllText($manifest, ((Get-Content $manifest -Raw).Replace('0.1.0.qualifier',$version)),$utf8)
$result = @{ bundle=$bundle; version=$version }
[IO.File]::WriteAllText((Join-Path $root 'build/live-latest.json'), ($result | ConvertTo-Json),$utf8)
$result | ConvertTo-Json
