@echo off
title ChargeOps 1-Click Full-Stack Demo Launcher
set "ROOT_DIR=%~dp0"
set "FRONTEND_DIR=%ROOT_DIR%..\..\chargeops-frontend"

echo =====================================================================
echo           CHARGEOPS - KHOI DONG HE THONG DEMO FULL-STACK
echo =====================================================================
echo.
echo [1/4] Dang build va khoi chay cac Docker container (Demo Full-Stack)...
echo       - PostgreSQL + PostGIS (port 5432)
echo       - Redis (port 6379)
echo       - Keycloak IAM (port 8080)
echo       - Backend Spring Boot API (port 8081)
echo       - Web Portal Nginx (port 5173)
echo       - Mailpit (port 1025 / 8025)
echo.

docker compose -f docker-compose.demo.yml --env-file .env up --build -d

if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Khong the khoi chay Docker Compose! Vui long kiem tra Docker Desktop da bat chua.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/4] Kiem tra trang thai cac container Docker...
docker compose -f docker-compose.demo.yml ps

echo.
echo [3/4] Khoi dong Driver Mobile App (Expo Metro - Port 8082)...
start "ChargeOps - Driver Mobile (Expo Port 8082)" cmd /k "cd /d "%FRONTEND_DIR%\chargeops-driver-mobile" && echo Dang chay Driver Mobile... && npm start"

echo.
echo [4/4] Dang kich hoat Tailscale Funnel cong khai qua Internet...
echo   - Mobile Driver Web (8082) -> https://thang.tail704409.ts.net/
tailscale funnel --bg http://localhost:8082

echo   - Backend API (8081)       -> https://thang.tail704409.ts.net/api
tailscale funnel --bg --set-path /api http://127.0.0.1:8081/api

echo   - Keycloak Auth (8080)     -> https://thang.tail704409.ts.net/realms
tailscale funnel --bg --set-path /realms http://127.0.0.1:8080/realms

echo   - Keycloak Res & JS (8080) -> https://thang.tail704409.ts.net/resources, /js
tailscale funnel --bg --set-path /resources http://127.0.0.1:8080/resources
tailscale funnel --bg --set-path /js http://127.0.0.1:8080/js

echo   - Web Portal (5173)        -> https://thang.tail704409.ts.net:8443/
tailscale funnel --bg --https=8443 http://localhost:5173

echo.
echo Kiem tra trang thai Tailscale Funnel:
tailscale funnel status

echo.
echo =====================================================================
echo                    HE THONG DEMO DA SAN SANG!
echo =====================================================================
echo * Mobile Driver App:        https://thang.tail704409.ts.net/
echo                             (Hoac quet ma QR tren cua so Expo bang Expo Go)
echo * Web Portal (Console):     https://thang.tail704409.ts.net:8443/
echo * Keycloak Account:         https://thang.tail704409.ts.net/realms/chargeops/account/
echo * Backend Swagger:          http://localhost:8081/swagger-ui.html
echo * SePay Webhook Endpoint:   https://thang.tail704409.ts.net/api/v1/webhooks/sepay
echo * Mailpit Web Mailbox:      http://localhost:8025/
echo =====================================================================
echo.
echo Meo xem log:
echo   - Backend API:    docker logs -f chargeops-backend-demo
echo   - Keycloak:       docker logs -f chargeops-keycloak-demo
echo.
echo Khi ket thuc buoi demo, ban co the tat bang:
echo   stop-demo.bat
echo =====================================================================
pause
