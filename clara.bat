@echo off
rem ============================================================
rem  Clara launcher (Windows CMD / PowerShell)
rem  Usage: clara.bat [optional provider-config-path]
rem  Build first: mvn -q -pl clara-application -am package -DskipTests
rem
rem  Pre-flight checks:
rem   0) Console code page: switch to UTF-8 (65001) so the UTF-8 output of the
rem      Java app is not garbled; the original code page is restored on exit.
rem   1) Python runtime (tool scripts need it); installs it on demand and
rem      exports the interpreter path via PYTHON_PATH.
rem   2) Credential env vars: every config under config/model-providers declares
rem      one key env var; each distinct env var is checked once and a missing
rem      value is prompted for (falls back to the main model when no provider
rem      config file is found).
rem ============================================================
setlocal EnableDelayedExpansion

rem Remember the launch directory: the project directory Clara analyses.
set "CLARA_WORK_DIR=%CD%"

rem Switch to the script directory (repo root) so config/ can be located.
cd /d "%~dp0"

rem ---- 0) Console code page ----
rem The Java app writes UTF-8; switch the console to UTF-8 (65001) and restore
rem the original code page on exit so the user's console is left unchanged.
set "ORIG_CP="
for /f "tokens=2 delims=:" %%C in ('chcp') do set "ORIG_CP=%%C"
set "ORIG_CP=!ORIG_CP: =!"
if not "!ORIG_CP!"=="65001" chcp 65001 >nul

if not exist "clara.jar" (
    echo [ERROR] clara.jar not found in this directory. Build it first:
    echo         mvn -q -pl clara-application -am package -DskipTests
    set "EXITCODE=1"
    goto :finish
)

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] java not found. Please install JDK 8+ and add it to PATH.
    set "EXITCODE=1"
    goto :finish
)

rem ---- 1) Python runtime ----
call :detect_python
if not defined PYTHON_PATH (
    call :warn "[WARN] Python runtime not found - tool scripts need Python."
    call :install_python
    call :detect_python
)
if not defined PYTHON_PATH (
    echo [ERROR] Python still not found. Install it manually and retry:
    echo         https://www.python.org/downloads/
    echo         Remember to check "Add python.exe to PATH" during setup.
    set "EXITCODE=1"
    goto :finish
)
echo [OK] Python runtime: !PYTHON_PATH!

rem ---- 2) Credential env vars for model providers ----
rem Every config under config\model-providers declares one credential env var, and the
rem startup availability check probes all of them, so check them all here too.
rem Several aliases may share one env var (deepseek-completion and deepseek-response
rem both use CLARA_DEEPSEEK_API_KEY), so each env var is checked only once.
set /a MODEL_CONFIG_COUNT=0
for %%F in ("config\model-providers\*.yml" "config\model-providers\*.yaml") do (
    if exist "%%~fF" (
        set /a MODEL_CONFIG_COUNT+=1
        call :check_provider_key "%%~fF"
    )
)
if !MODEL_CONFIG_COUNT! GTR 0 goto :launch
rem No provider config file found: fall back to checking only the main model's key.
call :resolve_model_config "%~1"
if not defined MODEL_CONFIG (
    call :warn "[WARN] Could not locate the main model config; skipping key check."
    goto :launch
)
call :resolve_key_env "!MODEL_CONFIG!"
if not defined KEY_ENV (
    echo [INFO] Config !MODEL_CONFIG! has no environmentVariable ^(or uses inline apiKey^); skipping key check.
    goto :launch
)
call :ensure_key "!KEY_ENV!"

:launch
echo.
rem -Dfile.encoding=UTF-8 is required here, not cosmetic: the Milvus Java SDK
rem encodes full-text search (BM25) text with String.getBytes(), i.e. the platform
rem default charset. Under a Chinese locale that is GBK, so the query goes out as
rem invalid UTF-8 and the Milvus server aborts while tokenizing it.
java -Dfile.encoding=UTF-8 -jar "clara.jar" %*
set "EXITCODE=%ERRORLEVEL%"

:finish
if defined ORIG_CP if not "!ORIG_CP!"=="65001" chcp !ORIG_CP! >nul 2>nul
endlocal & exit /b %EXITCODE%

rem ============================================================
rem  Subroutines
rem ============================================================

rem Detect a Python interpreter (python / python3 / py -3 / common install dirs) and set PYTHON_PATH.
:detect_python
set "PYTHON_PATH="
for %%P in (python.exe python3.exe) do (
    if not defined PYTHON_PATH (
        for /f "delims=" %%I in ('where %%P 2^>nul') do (
            if not defined PYTHON_PATH set "PYTHON_PATH=%%I"
        )
    )
)
if not defined PYTHON_PATH (
    where py >nul 2>nul
    if not errorlevel 1 (
        for /f "delims=" %%I in ('py -3 -c "import sys;print(sys.executable)" 2^>nul') do (
            if not defined PYTHON_PATH set "PYTHON_PATH=%%I"
        )
    )
)
if not defined PYTHON_PATH (
    for /d %%D in ("%LocalAppData%\Programs\Python\Python3*") do (
        if not defined PYTHON_PATH if exist "%%~fD\python.exe" set "PYTHON_PATH=%%~fD\python.exe"
    )
)
exit /b

rem Assist Python installation: use winget when available, otherwise print manual guidance.
:install_python
where winget >nul 2>nul
if errorlevel 1 (
    echo        winget not available; install Python 3 manually:
    echo        https://www.python.org/downloads/
    exit /b
)
set "ANS="
set /p "ANS=Install Python 3 automatically via winget now? [y/N]: "
if /i not "!ANS!"=="y" exit /b
echo        Installing Python 3 ^(this may take a few minutes^)...
winget install -e --id Python.Python.3.12 --scope user --accept-source-agreements --accept-package-agreements
exit /b

rem Resolve the main model config: command-line arg first; otherwise map clara.yml defaultModel to a file.
:resolve_model_config
set "MODEL_CONFIG=%~1"
if defined MODEL_CONFIG exit /b
set "DEFAULT_MODEL="
for /f "tokens=1,* delims=:" %%A in ('findstr /B /C:"defaultModel" "config\clara.yml" 2^>nul') do (
    if not defined DEFAULT_MODEL set "DEFAULT_MODEL=%%B"
)
if not defined DEFAULT_MODEL exit /b
set "DEFAULT_MODEL=!DEFAULT_MODEL: =!"
set DEFAULT_MODEL=%DEFAULT_MODEL:"=%
if exist "config\model-providers\!DEFAULT_MODEL!.yml" set "MODEL_CONFIG=config\model-providers\!DEFAULT_MODEL!.yml"
if not defined MODEL_CONFIG if exist "config\model-providers\!DEFAULT_MODEL!.yaml" set "MODEL_CONFIG=config\model-providers\!DEFAULT_MODEL!.yaml"
exit /b

rem Read environmentVariable from the model config (skipping commented lines).
:resolve_key_env
set "KEY_ENV="
for /f "tokens=1,* delims=:" %%A in ('findstr /C:"environmentVariable" "%~1" 2^>nul') do (
    set "TMP_KEY=%%A"
    for /f "tokens=* delims= " %%K in ("!TMP_KEY!") do set "TMP_KEY=%%K"
    if not defined KEY_ENV if not "!TMP_KEY:~0,1!"=="#" set "KEY_ENV=%%B"
)
if defined KEY_ENV set "KEY_ENV=!KEY_ENV: =!"
if defined KEY_ENV set KEY_ENV=%KEY_ENV:"=%
exit /b

rem Check the credential env var (current process env only); prompt when missing.
:ensure_key
set "KEY_NAME=%~1"
if defined %KEY_NAME% (
    echo [OK] Credential env var %KEY_NAME% is set.
    exit /b
)
call :warn "[WARN] Credential env var %KEY_NAME% is not set."
call :warn "[HINT] If you just set it via setx or System Properties, open a NEW terminal and run again."
set "KEY_VALUE="
set /p "KEY_VALUE=Enter value for %KEY_NAME% (press Enter to skip): "
if not defined KEY_VALUE (
    call :warn "[WARN] %KEY_NAME% was not provided; model calls may fail without credentials."
    exit /b
)
set "%KEY_NAME%=!KEY_VALUE!"
echo [OK] %KEY_NAME% set for this launch.
set "PERSIST="
set /p "PERSIST=Persist it to your user environment via setx? [y/N]: "
if /i "!PERSIST!"=="y" (
    setx "%KEY_NAME%" "!KEY_VALUE!" >nul
    echo [OK] %KEY_NAME% persisted ^(takes effect in new windows^).
)
exit /b

rem Print a warning line in yellow (falls back to plain text without PowerShell).
:warn
set "CLARA_WARN=%~1"
where powershell >nul 2>nul
if errorlevel 1 (
    echo !CLARA_WARN!
    exit /b
)
powershell -NoProfile -Command "Write-Host $env:CLARA_WARN -ForegroundColor Yellow" 2>nul
if errorlevel 1 echo !CLARA_WARN!
exit /b

rem Check one model config's credential env var (arg: config file path); an env var
rem shared by several aliases is checked once; prompt when missing.
:check_provider_key
set "PROVIDER_CFG=%~1"
set "PROVIDER_ALIAS=%~n1"
if /i "!PROVIDER_ALIAS:~-8!"==".example" exit /b
call :resolve_key_env "!PROVIDER_CFG!"
if not defined KEY_ENV (
    echo [INFO] Provider !PROVIDER_ALIAS!: no environmentVariable ^(or uses inline apiKey^); skipping key check.
    exit /b
)
call :key_checked "!KEY_ENV!"
if not errorlevel 1 exit /b
call :ensure_key "!KEY_ENV!"
exit /b

rem Record the credential env vars already checked (arg: env var name); returns
rem errorlevel 0 when it was checked before in this run, 1 when seen the first time.
:key_checked
if defined CHECKED_KEY_%~1 exit /b 0
set "CHECKED_KEY_%~1=1"
exit /b 1
