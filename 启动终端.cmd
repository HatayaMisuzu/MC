@echo off
chcp 65001 >nul 2>nul
call "%~dp0mcac.cmd" %*
exit /b %errorlevel%
