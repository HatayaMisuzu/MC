@echo off
setlocal
chcp 65001 >nul 2>nul
set "ROOT=%~dp0"
if exist "%ROOT%mcac.exe" set "MCAC=%ROOT%mcac.exe"& goto run
if exist "%ROOT%build\distributions\mcac-release\mcac.exe" set "MCAC=%ROOT%build\distributions\mcac-release\mcac.exe"& goto run
if exist "%ROOT%build\distributions\mcac-windows-x64\mcac-windows-x64.exe" set "MCAC=%ROOT%build\distributions\mcac-windows-x64\mcac-windows-x64.exe"& goto run
if exist "%ROOT%mcac-local\mcac.exe" set "MCAC=%ROOT%mcac-local\mcac.exe"& goto run
if exist "%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat" set "MCAC=%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat"& goto run
call "%ROOT%gradlew.bat" stageTerminalAtProjectRoot
if errorlevel 1 exit /b %errorlevel%
set "MCAC=%ROOT%mcac-local\mcac.exe"
:run
call "%MCAC%" %*
exit /b %errorlevel%
