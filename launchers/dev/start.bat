@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
cd /d "%BACKEND_DIR%"
title ChargeOps - Start local dev

echo Starting the local development environment...
if not exist ".env" (
  echo [ERROR] Backend .env is missing. Restore it from your local backup first.
  exit /b 1
)
where docker >nul 2>&1 || (echo [ERROR] Docker Desktop is unavailable.& exit /b 1)

REM Switch away from a previous demo without deleting any named Docker volumes.
echo Preparing only the services needed for local development...
docker compose --env-file .env -f docker-compose.demo.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous demo stack.& exit /b 1)
docker compose --env-file .env -f docker-compose.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous dev stack.& exit /b 1)

powershell -NoProfile -Command "$ports = @(5432,6379,9200,8080,9092,1025,8025); foreach ($port in $ports) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { Write-Host ('[ERROR] Infrastructure port ' + $port + ' is already in use. Close its server or container, then retry.'); exit 1 } }"
if errorlevel 1 exit /b 1

REM Use local Keycloak, Mailpit and Kafka endpoints even when .env contains public-demo values.
set "KEYCLOAK_PUBLIC_URL=http://localhost:8080"
docker compose --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 240 postgres redis elasticsearch keycloak kafka mailpit
if errorlevel 1 (echo [ERROR] Docker infrastructure did not become healthy.& exit /b 1)
powershell -NoProfile -Command "$expected = 'http://localhost:8080/realms/chargeops'; for ($attempt = 0; $attempt -lt 10; $attempt++) { try { $actual = (Invoke-RestMethod 'http://localhost:8080/realms/chargeops/.well-known/openid-configuration' -TimeoutSec 5).issuer; if ($actual -eq $expected) { Write-Host ('Keycloak issuer: ' + $actual); exit 0 }; Write-Host ('[ERROR] Local Keycloak advertises ' + $actual + ' instead of ' + $expected); exit 1 } catch { Start-Sleep -Seconds 2 } }; Write-Host '[ERROR] Could not verify the local Keycloak issuer.'; exit 1"
if errorlevel 1 (echo [ERROR] Local auth is not ready. Check the Keycloak container and rerun this launcher.& exit /b 1)
docker compose --env-file .env -f docker-compose.yml ps

echo.
echo Local infrastructure is ready. Start each application yourself in the IDE or terminal.
echo Backend:          mvnw.cmd spring-boot:run
echo Web console:      from chargeops-frontend\chargeops-web: npm run dev
echo Driver web:       from chargeops-frontend\chargeops-driver-mobile: npm run web
echo Web console:      http://localhost:5173
echo Driver web:       http://localhost:8082
echo Backend API:      http://localhost:8081
echo Keycloak:         http://localhost:8080
echo Mailpit inbox:    http://localhost:8025
echo Elasticsearch:    http://localhost:9200
echo Kafka:            localhost:9092
echo.
echo Grafana, Prometheus, Loki, Alloy, Kafka UI, gateway and Cloudflare Tunnel stay off in dev.
echo To stop Docker infrastructure, choose option 3 in START-CHARGEOPS.bat.
echo Stop Spring Boot, Vite and Expo in the IDE or their terminal windows.
