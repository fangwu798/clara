@echo off
setlocal
rem Clara local code scan entry:
rem   deploy\sonar\scan.cmd [arguments]
rem Arguments are forwarded to scan.ps1 (e.g. -RunTests, -SkipQualityGate, -Open).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scan.ps1" %*
exit /b %ERRORLEVEL%