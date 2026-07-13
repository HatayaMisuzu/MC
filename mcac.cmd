@echo off
setlocal
set "ROOT=%~dp0"
if exist "%ROOT%build\distributions\mcac-release\mcac.exe" set "MCAC=%ROOT%build\distributions\mcac-release\mcac.exe"& goto run
if exist "%ROOT%build\distributions\mcac-windows-x64\mcac-windows-x64.exe" set "MCAC=%ROOT%build\distributions\mcac-windows-x64\mcac-windows-x64.exe"& goto run
if exist "%ROOT%mcac-local\mcac.exe" set "MCAC=%ROOT%mcac-local\mcac.exe"& goto run
if exist "%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat" set "MCAC=%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat"& goto run
call "%ROOT%gradlew.bat" :terminal:terminal-app:installDist
if errorlevel 1 exit /b %errorlevel%
set "MCAC=%ROOT%terminal\terminal-app\build\install\mcac\bin\mcac.bat"
:run
call "%MCAC%" %*
exit /b %errorlevel%
