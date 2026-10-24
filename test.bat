@echo off
rem
rem test.bat -- run the pure-JVM logic tests.
rem
rem Only StitchEngine (crop arithmetic and the linked-crop rules) plus the test
rem harness are compiled. android.jar is used purely as a compile-time classpath:
rem the tested code never touches a real Android class at runtime, so the tests run
rem on any desktop JVM.
rem
rem Usage: test.bat [--sdk DIR] [--java-home DIR]
rem Exit code 0 means every test passed.
rem
setlocal

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "PLATFORM=android-36"
set "OUT=%ROOT%\build\testclasses"

:parse
if "%~1"=="" goto parsed
if /I "%~1"=="--sdk"       ( set "ANDROID_SDK_ROOT=%~2" & shift & shift & goto parse )
if /I "%~1"=="--java-home" ( set "JAVA_HOME=%~2" & shift & shift & goto parse )
echo unknown option: %~1
exit /b 2
:parsed

set "SDK=%ANDROID_SDK_ROOT%"
if "%SDK%"=="" set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"sdk.dir=" "%ROOT%\local.properties" 2^>nul') do set "SDK=%%b"
if "%SDK%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"

if "%JAVA_HOME%"=="" for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"java.home=" "%ROOT%\local.properties" 2^>nul') do set "JAVA_HOME=%%b"
if not "%JAVA_HOME%"=="" set "PATH=%JAVA_HOME%\bin;%PATH%"

set "ANDROID_JAR=%SDK%\platforms\%PLATFORM%\android.jar"

set "FAIL=0"
if not exist "%ANDROID_JAR%" (
    echo android.jar not found at %ANDROID_JAR%
    set "FAIL=1"
)
where javac >nul 2>&1
if errorlevel 1 (
    echo javac not found
    set "FAIL=1"
)
if "%FAIL%"=="1" (
    echo Set ANDROID_SDK_ROOT / JAVA_HOME, or local.properties.
    exit /b 2
)

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo ==^> compile
javac -encoding UTF-8 -nowarn -classpath "%ANDROID_JAR%" -d "%OUT%" ^
    "%ROOT%\src\com\equentr\subtitlestitch\StitchEngine.java" ^
    "%ROOT%\test\com\equentr\subtitlestitch\CropLogicTest.java"
if errorlevel 1 exit /b 1

echo ==^> run
java -cp "%OUT%" com.equentr.subtitlestitch.CropLogicTest
if errorlevel 1 exit /b 1

endlocal
