@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
set "FRONTEND_DIR=%BACKEND_DIR%\..\..\chargeops-frontend"
cd /d "%BACKEND_DIR%"
title ChargeOps - Start local dev

echo Starting the local development environment...
if not exist ".env" (
  echo [ERROR] Backend .env is missing. Restore it from your local backup first.
  exit /b 1
)
if not exist "%FRONTEND_DIR%\chargeops-web\package.json" (
  echo [ERROR] Frontend repository not found at "%FRONTEND_DIR%".
  exit /b 1
)
where docker >nul 2>&1 || (echo [ERROR] Docker Desktop is unavailable.& exit /b 1)
where npm >nul 2>&1 || (echo [ERROR] Node.js/npm is unavailable.& exit /b 1)
if not exist "mvnw.cmd" (echo [ERROR] Maven wrapper mvnw.cmd is missing.& exit /b 1)

REM Switch away from a previous demo without deleting any named Docker volumes.
echo Preparing only the services needed for local development...
docker compose --env-file .env -f docker-compose.demo.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous demo stack.& exit /b 1)
docker compose --env-file .env -f docker-compose.yml down --remove-orphans
if errorlevel 1 (echo [ERROR] Could not stop the previous dev stack.& exit /b 1)

powershell -NoProfile -Command "$ports = @(5432,6379,9200,8080,9092,1025,8025,5173,8081,8082); foreach ($port in $ports) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { Write-Host ('[ERROR] Port ' + $port + ' is already in use. Close its server window or container, then retry.'); exit 1 } }"
if errorlevel 1 exit /b 1

REM Use local Keycloak, Mailpit and Kafka endpoints even when .env contains public-demo values.
set "KEYCLOAK_PUBLIC_URL=http://localhost:8080"
docker compose --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 240 postgres redis elasticsearch keycloak kafka mailpit
if errorlevel 1 (echo [ERROR] Docker infrastructure did not become healthy.& exit /b 1)
docker compose --env-file .env -f docker-compose.yml ps

echo Opening the API, web console and driver web preview...
start "ChargeOps API - dev" /D "%BACKEND_DIR%" cmd /k "set SPRING_PROFILES_ACTIVE=dev&&set DATABASE_URL=jdbc:postgresql://localhost:5432/chargeops&&set ELASTICSEARCH_URL=http://localhost:9200&&set ELASTICSEARCH_ENABLED=true&&set KEYCLOAK_ISSUER_URI=http://localhost:8080/realms/chargeops&&set KEYCLOAK_JWK_SET_URI=http://localhost:8080/realms/chargeops/protocol/openid-connect/certs&&set KEYCLOAK_ADMIN_BASE_URL=http://localhost:8080&&set BACKEND_BASE_URL=http://localhost:8081&&set FRONTEND_BASE_URL=http://localhost:5173&&set CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:8082&&set SMTP_HOST=localhost&&set SMTP_PORT=1025&&set SMTP_AUTH=false&&set SMTP_STARTTLS=false&&set BOOKING_NOTIFICATION_EMAIL_ENABLED=true&&set BOOKING_NOTIFICATION_EMAIL_FROM=noreply@chargeops.test&&set BOOKING_NOTIFICATION_KAFKA_ENABLED=true&&set BOOKING_NOTIFICATION_CONSUMER_ENABLED=true&&set BOOKING_NOTIFICATION_OUTBOX_ENABLED=true&&set MANAGEMENT_HEALTH_KAFKA_ENABLED=true&&set KAFKA_BOOTSTRAP_SERVERS=localhost:9092&&mvnw.cmd spring-boot:run"
start "ChargeOps Web - dev" /D "%FRONTEND_DIR%\chargeops-web" cmd /k "set VITE_API_URL=http://localhost:8081/api/v1&&set VITE_USE_MOCKS=false&&set VITE_KEYCLOAK_ENABLED=true&&set VITE_KEYCLOAK_URL=http://localhost:8080&&set VITE_KEYCLOAK_REALM=chargeops&&set VITE_KEYCLOAK_CLIENT_ID=chargeops-web&&set VITE_DRIVER_APP_URL_DEV=http://localhost:8082&&set VITE_DRIVER_APP_URL=http://localhost:8082&&npm run dev"
start "ChargeOps Driver Web - dev" /D "%FRONTEND_DIR%\chargeops-driver-mobile" cmd /k "set EXPO_PUBLIC_KEYCLOAK_ISSUER_URL=http://localhost:8080/realms/chargeops&&set EXPO_PUBLIC_KEYCLOAK_ACCOUNT_URL=http://localhost:8080/realms/chargeops/account&&set EXPO_PUBLIC_KEYCLOAK_DRIVER_CLIENT_ID=chargeops-driver-mobile&&set EXPO_PUBLIC_OWNER_PORTAL_URL=http://localhost:5173&&set EXPO_PUBLIC_API_BASE_URL=http://localhost:8081&&set EXPO_PUBLIC_WS_URL=ws://localhost:8081/ws&&set EXPO_PUBLIC_USE_MOCKS=false&&npm run web"

echo.
echo Local development is starting in three separate windows. Wait for each ready message.
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
echo Stop Spring Boot, Vite and Expo with Ctrl+C in their windows.
