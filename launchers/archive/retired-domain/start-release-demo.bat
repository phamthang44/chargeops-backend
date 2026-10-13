@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
cd /d "%BACKEND_DIR%"
if "%BACKEND_IMAGE%"=="" (
  echo Set BACKEND_IMAGE to the approved GHCR image digest before running.
  echo Use the amd64 digest on most Windows desktops; use the arm64 digest on Oracle A1.
  exit /b 1
)
set "COMPOSE_FILES=--env-file .env -f docker-compose.demo.yml -f compose.domain.yml -f compose.mail-hostinger.yml -f compose.release-demo.yml"
docker compose %COMPOSE_FILES% config --quiet
if errorlevel 1 exit /b 1
docker compose %COMPOSE_FILES% up -d --wait --wait-timeout 180 kafka
if errorlevel 1 exit /b 1
docker compose %COMPOSE_FILES% up -d --no-build --no-deps --pull always --wait --wait-timeout 180 backend
if errorlevel 1 exit /b 1
echo Backend image started. Check logs and run the demo smoke checklist.
echo Public URL: https://chargeops.online
echo Driver Web preview: https://driver.chargeops.online
