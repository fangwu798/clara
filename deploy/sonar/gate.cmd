@echo off
setlocal
rem Clara quality gate entry:
rem   deploy\sonar\gate.cmd [arguments]
rem Arguments are forwarded to gate.ps1 (e.g. -LineCoverageThreshold 80).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gate.ps1" %*
exit /b %ERRORLEVEL%
