@echo off
rem
rem build.bat -- build the Subtitle Stitch APK without Gradle.
rem
rem Drives the Android SDK command line tools directly:
rem   aapt2 compile -> aapt2 link -> javac -> d8 -> package -> zipalign -> apksigner
rem
rem Configuration (highest priority first):
rem   1. command line flags
rem   2. environment variables (ANDROID_SDK_ROOT / ANDROID_HOME, JAVA_HOME,
rem      STITCH_KEYSTORE, STITCH_KS_PASS, STITCH_KS_ALIAS)
rem   3. local.properties in the project root (git-ignored)
rem
rem Usage:
rem   build.bat                 build and sign with an auto-generated debug key
rem   build.bat --release       label the output as a release build
rem   build.bat --install       build, then install on the connected device
rem   build.bat --out DIR       write build products to DIR
rem
setlocal EnableDelayedExpansion

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "PACKAGE=com.equentr.subtitlestitch"
set "VERSION_NAME=1.1.0"
set "VERSION_CODE=2"
set "MIN_SDK=29"
set "TARGET_SDK=36"
set "BUILD_TOOLS_VERSION=36.0.0"
set "PLATFORM=android-36"

set "OUT_DIR="
set "RELEASE=0"
set "INSTALL=0"
set "SERIAL="
set "ADB=adb"

rem --------------------------------------------------------------- arguments

:parse
if "%~1"=="" goto parsed
if /I "%~1"=="--release" ( set "RELEASE=1" & shift & goto parse )
if /I "%~1"=="--install" ( set "INSTALL=1" & shift & goto parse )
if /I "%~1"=="--out"     ( set "OUT_DIR=%~2" & shift & shift & goto parse )
if /I "%~1"=="--serial"  ( set "SERIAL=%~2" & shift & shift & goto parse )
if /I "%~1"=="--sdk"     ( set "ANDROID_SDK_ROOT=%~2" & shift & shift & goto parse )
if /I "%~1"=="--java-home" ( set "JAVA_HOME=%~2" & shift & shift & goto parse )
echo unknown option: %~1
exit /b 2
:parsed

rem ----------------------------------------------------------- configuration

set "SDK=%ANDROID_SDK_ROOT%"
if "%SDK%"=="" set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"sdk.dir=" "%ROOT%\local.properties" 2^>nul') do set "SDK=%%b"
if "%SDK%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"

if "%JAVA_HOME%"=="" for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"java.home=" "%ROOT%\local.properties" 2^>nul') do set "JAVA_HOME=%%b"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"

set "KS_PATH=%STITCH_KEYSTORE%"
if "%KS_PATH%"=="" for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"keystore.path=" "%ROOT%\local.properties" 2^>nul') do set "KS_PATH=%%b"
set "KS_PASS=%STITCH_KS_PASS%"
if "%KS_PASS%"=="" set "KS_PASS=android"
set "KS_ALIAS=%STITCH_KS_ALIAS%"
if "%KS_ALIAS%"=="" set "KS_ALIAS=debug"

if "%OUT_DIR%"=="" set "OUT_DIR=%ROOT%\build"

set "BUILD_TOOLS=%SDK%\build-tools\%BUILD_TOOLS_VERSION%"
set "ANDROID_JAR=%SDK%\platforms\%PLATFORM%\android.jar"

rem ---------------------------------------------------------------- preflight

set "FAIL=0"
if not exist "%SDK%\" (
    echo Android SDK not found. Set ANDROID_SDK_ROOT or sdk.dir in local.properties.
    set "FAIL=1"
) else (
    if not exist "%BUILD_TOOLS%\aapt2.exe" (
        echo aapt2 missing: install build-tools;%BUILD_TOOLS_VERSION%
        set "FAIL=1"
    )
    if not exist "%ANDROID_JAR%" (
        echo android.jar missing: install platforms;%PLATFORM%
        set "FAIL=1"
    )
)
where javac >nul 2>&1
if errorlevel 1 (
    echo javac not found: install JDK 17+ or set JAVA_HOME
    set "FAIL=1"
)
if "%FAIL%"=="1" exit /b 2

echo build output dir: %OUT_DIR%
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

rem wipe stale intermediates
if exist "%OUT_DIR%\gen"          rmdir /s /q "%OUT_DIR%\gen"
if exist "%OUT_DIR%\classes"      rmdir /s /q "%OUT_DIR%\classes"
if exist "%OUT_DIR%\dex"          rmdir /s /q "%OUT_DIR%\dex"
if exist "%OUT_DIR%\res-compiled" rmdir /s /q "%OUT_DIR%\res-compiled"
mkdir "%OUT_DIR%\gen"
mkdir "%OUT_DIR%\classes"
mkdir "%OUT_DIR%\dex"
mkdir "%OUT_DIR%\res-compiled"

rem -------------------------------------------------------------------- res

echo ==^> aapt2 compile
for /r "%ROOT%\res" %%f in (*) do (
    "%BUILD_TOOLS%\aapt2.exe" compile -o "%OUT_DIR%\res-compiled" "%%f" || exit /b 1
)

echo ==^> aapt2 link
set "FLATS="
for %%f in ("%OUT_DIR%\res-compiled\*.flat") do set "FLATS=!FLATS! "%%~f""
if "!FLATS!"=="" (
    echo no resources compiled
    exit /b 1
)
"%BUILD_TOOLS%\aapt2.exe" link ^
    -o "%OUT_DIR%\res.zip" ^
    -I "%ANDROID_JAR%" ^
    --manifest "%ROOT%\AndroidManifest.xml" ^
    --java "%OUT_DIR%\gen" ^
    --min-sdk-version %MIN_SDK% ^
    --target-sdk-version %TARGET_SDK% ^
    --version-code %VERSION_CODE% ^
    --version-name %VERSION_NAME% ^
    --auto-add-overlay ^
    !FLATS!
if errorlevel 1 exit /b 1

rem ------------------------------------------------------------------- java

echo ==^> javac
del /q "%OUT_DIR%\sources.txt" 2>nul
for /r "%ROOT%\src" %%f in (*.java) do echo %%f>>"%OUT_DIR%\sources.txt"
for /r "%OUT_DIR%\gen" %%f in (*.java) do echo %%f>>"%OUT_DIR%\sources.txt"
set "SRCLIST=%OUT_DIR%\sources.txt"
pushd "%OUT_DIR%"
call javac -source 17 -target 17 -encoding UTF-8 -nowarn -classpath "%ANDROID_JAR%" -d "%OUT_DIR%\classes" "@%SRCLIST%"
set "JRC=%ERRORLEVEL%"
popd
if not "%JRC%"=="0" exit /b 1

rem -------------------------------------------------------------------- dex

echo ==^> d8
del /q "%OUT_DIR%\classes.txt" 2>nul
for /r "%OUT_DIR%\classes" %%f in (*.class) do echo %%f>>"%OUT_DIR%\classes.txt"
set "CLSLIST=%OUT_DIR%\classes.txt"
pushd "%OUT_DIR%"
call "%BUILD_TOOLS%\d8.bat" --release --min-api %MIN_SDK% --lib "%ANDROID_JAR%" --output "%OUT_DIR%\dex" "@%CLSLIST%"
set "D8RC=%ERRORLEVEL%"
popd
if not "%D8RC%"=="0" exit /b 1
if errorlevel 1 exit /b 1
if not exist "%OUT_DIR%\dex\classes.dex" (
    echo d8 produced no classes.dex
    exit /b 1
)

rem ---------------------------------------------------------------- package

echo ==^> package
del /q "%OUT_DIR%\unsigned.apk" "%OUT_DIR%\aligned.apk" "%OUT_DIR%\SubtitleStitch.apk" 2>nul
if exist "%OUT_DIR%\pack" rmdir /s /q "%OUT_DIR%\pack"
mkdir "%OUT_DIR%\pack"
rem expand the resource apk, add the dex, drop any signature files
powershell -NoProfile -Command "Expand-Archive -Force -LiteralPath '%OUT_DIR%\res.zip' -DestinationPath '%OUT_DIR%\pack'"
if exist "%OUT_DIR%\pack\META-INF" rmdir /s /q "%OUT_DIR%\pack\META-INF"
copy /y "%OUT_DIR%\dex\classes.dex" "%OUT_DIR%\pack\classes.dex" >nul
powershell -NoProfile -Command "Compress-Archive -Force -Path '%OUT_DIR%\pack\*' -DestinationPath '%OUT_DIR%\unsigned.apk'"
if errorlevel 1 exit /b 1

echo ==^> zipalign
"%BUILD_TOOLS%\zipalign.exe" -p -f 4 "%OUT_DIR%\unsigned.apk" "%OUT_DIR%\aligned.apk"
if errorlevel 1 exit /b 1

rem ------------------------------------------------------------------- sign

if "%KS_PATH%"=="" set "KS_PATH=%OUT_DIR%\debug.keystore"
if not exist "%KS_PATH%" (
    echo ==^> generate debug keystore
    keytool -genkeypair -keystore "%KS_PATH%" -storetype PKCS12 ^
        -storepass android -keypass android -alias debug ^
        -keyalg RSA -keysize 2048 -validity 10000 ^
        -dname "CN=Android Debug,O=Android,C=US" >nul 2>&1
    set "KS_PASS=android"
    set "KS_ALIAS=debug"
)

echo ==^> apksigner
"%BUILD_TOOLS%\apksigner.bat" sign ^
    --ks "%KS_PATH%" ^
    --ks-pass "pass:%KS_PASS%" ^
    --ks-key-alias %KS_ALIAS% ^
    --key-pass "pass:%KS_PASS%" ^
    --min-sdk-version %MIN_SDK% ^
    --v1-signing-enabled true ^
    --v2-signing-enabled true ^
    --out "%OUT_DIR%\SubtitleStitch.apk" ^
    "%OUT_DIR%\aligned.apk"
if errorlevel 1 exit /b 1

echo ==^> verify
"%BUILD_TOOLS%\apksigner.bat" verify --print-certs "%OUT_DIR%\SubtitleStitch.apk" >nul
if errorlevel 1 exit /b 1

echo.
echo BUILD OK: %OUT_DIR%\SubtitleStitch.apk

rem ---------------------------------------------------------------- install

if "%INSTALL%"=="1" (
    set "ADB_ARGS="
    if not "!SERIAL!"=="" set "ADB_ARGS=-s !SERIAL!"
    echo ==^> install
    %ADB% !ADB_ARGS! push "%OUT_DIR%\SubtitleStitch.apk" /data/local/tmp/stitch.apk
    %ADB% !ADB_ARGS! shell pm install -r /data/local/tmp/stitch.apk
)

endlocal
