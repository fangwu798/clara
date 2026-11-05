@echo off
rem ============================================================
rem  Clara local dependency stack deployment (Windows)
rem  Usage: deploy.bat [up|down|stop|start|restart|status|logs|pull|milvus|graph]
rem  Default action: up
rem
rem  The stack is defined in docker-compose.yml next to this script:
rem    - Milvus: etcd + MinIO + standalone + attu
rem    - codebase-memory-mcp
rem ============================================================
setlocal EnableDelayedExpansion

rem Keep the console readable: the script and Compose output are UTF-8.
set "ORIG_CP="
for /f "tokens=2 delims=:" %%C in ('chcp') do set "ORIG_CP=%%C"
set "ORIG_CP=!ORIG_CP: =!"
if not "!ORIG_CP!"=="65001" chcp 65001 >nul

cd /d "%~dp0"
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=up"

where docker >nul 2>nul
if errorlevel 1 (
    echo [ERROR] docker not found. Install Docker Desktop and add it to PATH.
    set "EXITCODE=1"
    goto :finish
)

set "COMPOSE="
docker compose version >nul 2>nul
if not errorlevel 1 (
    set "COMPOSE=docker compose"
) else (
    where docker-compose >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Docker Compose not found ^(neither "docker compose" nor "docker-compose"^).
        set "EXITCODE=1"
        goto :finish
    )
    set "COMPOSE=docker-compose"
)

rem Compose only auto-loads .env from this directory. When an explicit --env-file
rem is passed, it no longer auto-loads, so both candidate files are listed here.
set "ENV_ARGS="
if exist ".env" set "ENV_ARGS=--env-file .env"
if exist "codebase-memory-mcp\.env" set "ENV_ARGS=!ENV_ARGS! --env-file codebase-memory-mcp\.env"

if /i "%ACTION%"=="up" goto :up
if /i "%ACTION%"=="down" goto :down
if /i "%ACTION%"=="stop" goto :stop
if /i "%ACTION%"=="start" goto :start
if /i "%ACTION%"=="restart" goto :restart
if /i "%ACTION%"=="status" goto :status
if /i "%ACTION%"=="ps" goto :status
if /i "%ACTION%"=="logs" goto :logs
if /i "%ACTION%"=="pull" goto :pull
if /i "%ACTION%"=="milvus" goto :milvus
if /i "%ACTION%"=="graph" goto :graph
if /i "%ACTION%"=="help" goto :help
if /i "%ACTION%"=="-h" goto :help
if /i "%ACTION%"=="--help" goto :help
goto :unknown

:up
call :run up -d
goto :done

:down
call :run down
goto :done

:stop
call :run stop
goto :done

:start
call :run start
goto :done

:restart
call :run restart
goto :done

:status
call :run ps
goto :done

:logs
call :run logs -f --tail=200
goto :done

:pull
rem codebase-memory-mcp is built locally from deploy/codebase-memory-mcp.
call :run pull etcd minio standalone attu
goto :done

:milvus
call :run up -d etcd minio standalone attu
goto :done

:graph
call :run up -d codebase-memory-mcp
goto :done

:unknown
echo [ERROR] Unknown action: %ACTION%
echo.
call :usage
set "EXITCODE=2"
goto :finish

:help
call :usage
set "EXITCODE=0"
goto :finish

:done
if not defined EXITCODE set "EXITCODE=0"
goto :finish

:run
%COMPOSE% %ENV_ARGS% %*
set "EXITCODE=%ERRORLEVEL%"
exit /b

:usage
echo Usage: deploy.bat [action]
echo.
echo   up       Start the whole stack ^(default^)
echo   down     Stop and remove containers ^(volumes are kept^)
echo   stop     Stop containers without removing them
echo   start    Start previously stopped containers
echo   restart  Restart containers
echo   status   Show container status ^(alias: ps^)
echo   logs     Follow logs
echo   pull     Pull third-party images ^(etcd/minio/milvus/attu^)
echo   milvus   Start only Milvus and its dependencies
echo   graph    Start/build only codebase-memory-mcp
echo   help     Show this help
echo.
echo Examples:
echo   deploy.bat up
echo   deploy.bat milvus
echo   deploy.bat logs
exit /b

:finish
if defined ORIG_CP if not "!ORIG_CP!"=="65001" chcp !ORIG_CP! >nul 2>nul
endlocal & exit /b %EXITCODE%
