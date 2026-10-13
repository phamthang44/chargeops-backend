@echo off
setlocal
cd /d "%~dp0"
title ChargeOps - Launcher menu

:MENU
cls
echo ==========================================
echo             CHARGEOPS LAUNCHER
echo ==========================================
echo 1. Start local development
echo 2. Start public demo ^(domain + Cloudflare Tunnel^)
echo 3. Stop local Docker services
echo 4. Stop public demo and Tunnel
echo 5. Exit
echo.
choice /C 12345 /N /M "Choose an action: "
if errorlevel 5 exit /b 0
if errorlevel 4 goto STOP_DEMO
if errorlevel 3 goto STOP_DEV
if errorlevel 2 goto START_DEMO
if errorlevel 1 goto START_DEV

:START_DEV
call "%~dp0launchers\dev\start.bat"
goto RETURN_MENU

:START_DEMO
call "%~dp0launchers\demo\start.bat"
goto RETURN_MENU

:STOP_DEV
call "%~dp0launchers\dev\stop.bat"
goto RETURN_MENU

:STOP_DEMO
call "%~dp0launchers\demo\stop.bat"
goto RETURN_MENU

:RETURN_MENU
echo.
pause
goto MENU
