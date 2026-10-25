param(
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Serial,
    [string]$OutDir = (Join-Path $PSScriptRoot 'build\device-tests'),
    [string]$EngineSource = (Join-Path $PSScriptRoot 'src\com\equentr\subtitlestitch\StitchEngine.java')
)

# Real Android graphics tests; no APK installation or library dependencies.
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_HOME }
if (!$Sdk -or !$JavaHome) {
    throw 'Set ANDROID_SDK_ROOT and JAVA_HOME, or pass -Sdk and -JavaHome.'
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$d8 = Join-Path $Sdk 'build-tools\36.0.0\d8.bat'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
foreach ($tool in @($javac, $adb, $d8, $androidJar, $EngineSource)) {
    if (!(Test-Path -LiteralPath $tool)) { throw "Required file not found: $tool" }
}
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$runId = [guid]::NewGuid().ToString('N')
$output = Join-Path ([System.IO.Path]::GetFullPath($OutDir)) $runId
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$test = Join-Path $PSScriptRoot 'test\com\equentr\subtitlestitch\FramePreservationTest.java'
& $javac -source 17 -target 17 -encoding UTF-8 -nowarn -classpath $androidJar -d $classes $EngineSource $test
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$classFiles = (Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class').FullName
& $d8 --min-api 29 --lib $androidJar --output $dex $classFiles
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$remoteDex = "/data/local/tmp/subtitlestitch-frame-tests-$runId.dex"
$remoteDirectory = "/data/local/tmp/subtitlestitch-frame-tests-$runId"
& $adb @deviceArgs push (Join-Path $dex 'classes.dex') $remoteDex
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$testExit = 1
try {
    & $adb @deviceArgs shell "CLASSPATH=$remoteDex app_process /system/bin com.equentr.subtitlestitch.FramePreservationTest $remoteDirectory"
    $testExit = $LASTEXITCODE
} finally {
    # Verify the exact, unique paths before removing only this run's artifacts.
    & $adb @deviceArgs shell ls -l $remoteDex
    if ($LASTEXITCODE -eq 0) { & $adb @deviceArgs shell rm $remoteDex }
    & $adb @deviceArgs shell ls -ld $remoteDirectory
    if ($LASTEXITCODE -eq 0) { & $adb @deviceArgs shell rmdir $remoteDirectory }
}
exit $testExit
