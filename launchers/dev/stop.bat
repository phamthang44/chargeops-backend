@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
cd /d "%BACKEND_DIR%"
title ChargeOps - Stop local dev
echo Stopping local Docker services. Named Docker volumes and database data are kept.
docker compose --env-file .env -f docker-compose.yml down --remove-orphans
if errorlevel 1 exit /b %ERRORLEVEL%
echo Docker services stopped. Stop Spring Boot, Vite and Expo with Ctrl+C in their windows.
