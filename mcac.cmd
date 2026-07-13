@echo off
setlocal
chcp 65001 >nul 2>nul
set "ROOT=%~dp0"
set "LAUNCHER=mcac.exe"
if not "%~1"=="" set "LAUNCHER=mcac-cli.exe"
if exist "%ROOT%%LAUNCHER%" set "MCAC=%ROOT%%LAUNCHER%"& goto run
if exist "%ROOT%build\distributions\mcac-release\%LAUNCHER%" set "MCAC=%ROOT%build\distributions\mcac-release\%LAUNCHER%"& goto run
if exist "%ROOT%build\distributions\mcac-windows-x64\mcac-windows-x64.exe" set "MCAC=%ROOT%build\distributions\mcac-windows-x64\mcac-windows-x64.exe"& goto run
if exist "%ROOT%mcac-local\%LAUNCHER%" set "MCAC=%ROOT%mcac-local\%LAUNCHER%"& goto run
if exist "%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat" set "MCAC=%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat"& goto run
call "%ROOT%gradlew.bat" stageTerminalAtProjectRoot
if errorlevel 1 exit /b %errorlevel%
set "MCAC=%ROOT%mcac-local\%LAUNCHER%"
:run
call "%MCAC%" %*
exit /b %errorlevel%
