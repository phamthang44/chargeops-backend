@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
set "FRONTEND_DIR=%BACKEND_DIR%\..\..\chargeops-frontend"
cd /d "%BACKEND_DIR%"
title ChargeOps - Start public demo

echo Starting the public demo on chargeops.online...
if not exist ".env" (echo [ERROR] Backend .env is missing.& exit /b 1)
if not exist ".env.cloudflared" (echo [ERROR] .env.cloudflared is missing. Configure the existing Cloudflare Tunnel token first.& exit /b 1)
if not exist "%FRONTEND_DIR%\chargeops-driver-mobile\package.json" (echo [ERROR] Frontend repository not found at "%FRONTEND_DIR%".& exit /b 1)
where docker >nul 2>&1 || (echo [ERROR] Docker Desktop is unavailable.& exit /b 1)
where npm >nul 2>&1 || (echo [ERROR] Node.js/npm is unavailable.& exit /b 1)

findstr /R /C:"^[ ]*CLOUDFLARE_TUNNEL_TOKEN=[^ ]" .env.cloudflared >nul
if errorlevel 1 (echo [ERROR] .env.cloudflared has no configured CLOUDFLARE_TUNNEL_TOKEN.& exit /b 1)

set "COMPOSE_FILES=--env-file .env --env-file .env.cloudflared -f docker-compose.demo.yml -f compose.domain.yml -f compose.domain-demo.yml -f compose.mail-hostinger.yml"
docker compose %COMPOSE_FILES% --profile public config --quiet
if errorlevel 1 (echo [ERROR] Compose configuration is invalid. Check local .env settings, especially Hostinger SMTP.& exit /b 1)

REM Switching modes stops containers only; Docker volumes and database contents are preserved.
echo Stopping old local/demo containers without deleting data volumes...
docker compose --env-file .env -f docker-compose.demo.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous demo stack.& exit /b 1)
docker compose --env-file .env -f docker-compose.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous dev stack.& exit /b 1)

powershell -NoProfile -Command "$ports = @(5432,6379,9200,8080,8081,8082,8088,9092,1025,8025,5173); foreach ($port in $ports) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { Write-Host ('[ERROR] Port ' + $port + ' is already in use. Close its server window or container, then retry.'); exit 1 } }"
if errorlevel 1 exit /b 1

echo Building and starting the full demo, live Hostinger mail and Cloudflare Tunnel...
docker compose %COMPOSE_FILES% --profile public up --build -d --wait --wait-timeout 300
if errorlevel 1 (echo [ERROR] Demo services failed to start. Use docker compose %COMPOSE_FILES% logs for details.& exit /b 1)
docker compose %COMPOSE_FILES% --profile public ps

echo Starting the driver web preview used by driver.chargeops.online...
start "ChargeOps Driver Web - public demo" /D "%FRONTEND_DIR%\chargeops-driver-mobile" cmd /k "set EXPO_PUBLIC_KEYCLOAK_ISSUER_URL=https://chargeops.online/realms/chargeops&&set EXPO_PUBLIC_KEYCLOAK_ACCOUNT_URL=https://chargeops.online/realms/chargeops/account&&set EXPO_PUBLIC_OWNER_PORTAL_URL=https://chargeops.online&&set EXPO_PUBLIC_API_BASE_URL=https://chargeops.online&&set EXPO_PUBLIC_WS_URL=wss://chargeops.online/ws&&set EXPO_PUBLIC_USE_MOCKS=false&&npm start -- --web"

echo.
echo Public demo startup requested. Wait until Expo reports that web is ready.
echo Console:          https://chargeops.online
echo Driver:           https://driver.chargeops.online
echo Keycloak:         https://chargeops.online/realms/chargeops/account/
echo Grafana:          https://chargeops.online/grafana/
echo Backend API:      https://chargeops.online/api/v1
echo Mailpit is disabled in this mode; booking notifications use Hostinger SMTP.
echo To stop Docker and the Tunnel, choose option 4 in START-CHARGEOPS.bat.
echo Stop Expo Web with Ctrl+C in its window.
