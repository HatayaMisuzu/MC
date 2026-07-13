@echo off
setlocal
set "MCAC_HOME=%~dp0"
"%MCAC_HOME%runtime-app.exe" %*
exit /b %errorlevel%
