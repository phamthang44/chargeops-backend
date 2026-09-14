@echo off
title ChargeOps 1-Click Full-Stack Demo Launcher
echo =====================================================================
echo           CHARGEOPS - KHOI DONG HE THONG DEMO FULL-STACK
echo =====================================================================
echo.
echo [1/3] Dang build va khoi chay cac Docker container (Demo Full-Stack)...
echo       - PostgreSQL + PostGIS (port 5432)
echo       - Redis (port 6379)
echo       - Keycloak IAM (port 8080)
echo       - Backend Spring Boot API (port 8081)
echo       - Web Portal Nginx (port 5173)
echo       - Mailpit (port 1025 / 8025)
echo.

docker compose -f docker-compose.demo.yml up --build -d

if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Khong the khoi chay Docker Compose! Vui long kiem tra Docker Desktop da bat chua.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/3] Dang kich hoat Tailscale Funnel cong khai qua Internet...
echo   - Keycloak (8080)       -> https://thang.tail704409.ts.net/
tailscale funnel --bg http://127.0.0.1:8080

echo   - Backend API (8081)    -> https://thang.tail704409.ts.net/api
tailscale funnel --bg --set-path /api http://127.0.0.1:8081/api

echo   - Web Portal (5173)     -> https://thang.tail704409.ts.net:8443/
tailscale funnel --bg --https=8443 http://localhost:5173

echo.
echo [3/3] Kiem tra trang thai Tailscale Funnel:
tailscale funnel status

echo.
echo =====================================================================
echo                    HE THONG DEMO DA SAN SANG!
echo =====================================================================
echo * Web Portal (Console):     https://thang.tail704409.ts.net:8443/
echo * Keycloak Login:           https://thang.tail704409.ts.net/
echo * Backend Swagger:          http://localhost:8081/swagger-ui.html
echo * SePay Webhook Endpoint:   https://thang.tail704409.ts.net/api/v1/webhooks/sepay
echo * Mailpit Web Mailbox:      http://localhost:8025/
echo =====================================================================
echo.
echo Khi ket thuc buoi demo, ban co the tat bang lenh:
echo   docker compose -f docker-compose.demo.yml down
echo =====================================================================
pause
