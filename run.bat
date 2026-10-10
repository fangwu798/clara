@echo off
rem ============================================================
rem  Clara launcher (Windows CMD)
rem  Usage: run.bat [optional provider-config-path]
rem  Build first: mvn -q -pl clara-application -am package -DskipTests
rem ============================================================
setlocal

rem Switch to the script directory (repo root) so that config/ can be located.
cd /d "%~dp0"

if not exist "clara.jar" (
    echo [ERROR] clara.jar not found in this directory. Build it first:
    echo         mvn -q -pl clara-application -am package -DskipTests
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] java not found. Please install JDK 8+ and add it to PATH.
    exit /b 1
)

java -jar "clara.jar" %*
set "EXITCODE=%ERRORLEVEL%"
endlocal & exit /b %EXITCODE%
