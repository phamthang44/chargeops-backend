@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
cd /d "%BACKEND_DIR%"
title ChargeOps - Start public backend demo

echo Starting ChargeOps public backend services...
if not exist ".env" (echo [ERROR] Backend .env is missing.& exit /b 1)
if not exist ".env.demo" (echo [ERROR] Backend .env.demo is missing.& exit /b 1)
if not exist ".env.cloudflared" (echo [ERROR] .env.cloudflared is missing. Configure the existing Cloudflare Tunnel token first.& exit /b 1)
where docker >nul 2>&1 || (echo [ERROR] Docker Desktop is unavailable.& exit /b 1)

findstr /R /C:"^[ ]*CLOUDFLARE_TUNNEL_TOKEN=[^ ]" .env.cloudflared >nul
if errorlevel 1 (echo [ERROR] .env.cloudflared has no configured CLOUDFLARE_TUNNEL_TOKEN.& exit /b 1)

set "COMPOSE_FILES=--env-file .env --env-file .env.demo --env-file .env.cloudflared -f docker-compose.demo.yml -f compose.domain.yml -f compose.mail-hostinger.yml"
docker compose %COMPOSE_FILES% --profile public config --quiet
if errorlevel 1 (echo [ERROR] Compose configuration is invalid. Check local .env settings, especially Hostinger SMTP.& exit /b 1)

REM Switching modes stops containers only; Docker volumes and database contents are preserved.
echo Stopping old local/demo containers without deleting data volumes...
docker compose --env-file .env -f docker-compose.demo.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous demo stack.& exit /b 1)
docker compose --env-file .env -f docker-compose.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous dev stack.& exit /b 1)

powershell -NoProfile -Command "$ports = @(5432,6379,8080,8081,8088,9092); foreach ($port in $ports) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { Write-Host ('[ERROR] Port ' + $port + ' is already in use. Close its server window or container, then retry.'); exit 1 } }"
if errorlevel 1 exit /b 1

echo Building and starting backend services, Hostinger mail and Cloudflare Tunnel...
docker compose %COMPOSE_FILES% --profile public up --build -d --wait --wait-timeout 300
if errorlevel 1 (echo [ERROR] Demo services failed to start. Use docker compose %COMPOSE_FILES% logs for details.& exit /b 1)
docker compose %COMPOSE_FILES% --profile public ps

echo.
echo Backend demo startup requested. Static frontends are deployed separately.
echo Marketing:        https://www.chargeops.online
echo Console:          https://console.chargeops.online
echo Driver:           https://driver.chargeops.online
echo Keycloak:         https://auth.chargeops.online/realms/chargeops/account/
echo Grafana:          https://grafana.chargeops.online/
echo Backend API:      https://api.chargeops.online/api/v1
echo Booking notifications use Hostinger SMTP through Kafka. Mailpit is not part of this stack.
echo To stop Docker and the Tunnel, choose option 4 in START-CHARGEOPS.bat.
