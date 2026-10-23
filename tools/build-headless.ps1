<# Compila el servidor independiente contra una distribución real de Archi. #>
[CmdletBinding()]
param(
    [string]$ArchiHome = 'C:\Program Files\Archi',
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$build = Join-Path $root ('build\headless\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$stage = Join-Path $build 'io.github.h0w4r.axioma.headless_0.1.0'
$classes = Join-Path $build 'clases'
New-Item -ItemType Directory -Force -Path $stage, $classes | Out-Null
$jars = @(Get-ChildItem (Join-Path $ArchiHome 'plugins') -Recurse -Filter '*.jar' -File)
$lib = Join-Path $root 'net.vheerden.archi.mcp\lib'
$jars += @(Get-ChildItem $lib -Recurse -Filter '*.jar' -File)
# javac no resuelve los Bundle-ClassPath anidados de PDE por sí solo.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$nested = @()
foreach ($dependency in $jars) {
    $zip = [IO.Compression.ZipFile]::OpenRead($dependency.FullName)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.Name.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
                $folder = Join-Path $build ('dependencias\' + $dependency.BaseName)
                New-Item -ItemType Directory -Force -Path $folder | Out-Null
                $target = Join-Path $folder $entry.Name
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $false)
                $nested += Get-Item $target
            }
        }
    } finally { $zip.Dispose() }
}
$jars += $nested
$utf8 = [Text.UTF8Encoding]::new($false)
$argsFile = Join-Path $build 'javac.args'
$lines = @('--release', '21', '-encoding', 'UTF-8', '-classpath',
    ('"' + (($jars.FullName -join ';') -replace '\\', '/') + '"'),
    '-d', ('"' + ($classes -replace '\\', '/') + '"'))
$lines += Get-ChildItem (Join-Path $root 'headless\src') -Recurse -Filter '*.java' | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
# Reutilizar geometría de la línea base sin compilar su despacho SWT ni su modelo activo.
foreach ($relative in @('model/ElkLayoutEngine.java', 'model/LayoutNode.java', 'model/LayoutEdge.java',
    'model/ElkLayoutResult.java', 'model/ModelAccessException.java', 'response/ErrorCode.java',
    'response/dto/AbsoluteBendpointDto.java', 'response/dto/ViewPositionSpec.java')) {
    $source = Join-Path $root ('net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/' + $relative)
    $lines += '"' + ($source -replace '\\', '/') + '"'
}
[IO.File]::WriteAllLines($argsFile, $lines, $utf8)
& (Join-Path $JavaHome 'bin\javac.exe') "@$argsFile"
if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación headless.' }
Copy-Item (Join-Path $classes '*') $stage -Recurse
Copy-Item (Join-Path $root 'headless\META-INF') $stage -Recurse
$bundleVersion = '0.1.0.local' + (Get-Date -Format 'yyyyMMddHHmmssfff')
$manifestPath = Join-Path $stage 'META-INF\MANIFEST.MF'
[IO.File]::WriteAllText($manifestPath, ((Get-Content $manifestPath -Raw).Replace('0.1.0.qualifier', $bundleVersion)), $utf8)
Copy-Item (Join-Path $root 'headless\plugin.xml') $stage
# Sólo incorporar dependencias declaradas, no Jetty ni el adaptador gráfico legado.
$manifest = Get-Content (Join-Path $stage 'META-INF\MANIFEST.MF') -Raw
foreach ($match in [regex]::Matches($manifest, 'lib/[^\s,]+\.jar')) {
    $relative = $match.Value
    $target = Join-Path $stage $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    if ($relative.StartsWith('lib/render/')) {
        # El rasterizador procede de la distribución oficial de Archi, no de una descarga flotante.
        $svg = @(Get-ChildItem (Join-Path $ArchiHome 'plugins') -Directory -Filter 'com.archimatetool.export.svg_*')[0]
        Copy-Item (Join-Path $svg.FullName ('lib/' + [IO.Path]::GetFileName($relative))) $target
    } else { Copy-Item (Join-Path (Join-Path $root 'net.vheerden.archi.mcp') $relative) $target }
}
$result = @{ bundle = $stage; version = $bundleVersion; archi = $ArchiHome; compiledAt = (Get-Date).ToString('o') }
[IO.File]::WriteAllText((Join-Path $root 'build\headless-latest.json'), ($result | ConvertTo-Json), $utf8)
$result | ConvertTo-Json
