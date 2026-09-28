@echo off
title ChargeOps Dev Environment Launcher
echo =====================================================================
echo                CHARGEOPS - KHOI DONG MOI TRUONG DEV
echo =====================================================================
echo.
echo [1/3] Don dep cac container demo (neu co)...
docker compose -f docker-compose.demo.yml down 2>nul

REM Dam bao thu muc logs ton tai de Alloy mount khong loi
if not exist "logs" mkdir logs

echo.
echo [2/3] Khoi dong cac container ha tang DEV va Observability...
echo       - Core DB ^& IAM: Postgres, Redis, Keycloak, Mailpit
echo       - Observability:  Loki, Alloy, Prometheus, Grafana
docker compose -f docker-compose.yml up -d

if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Khong the khoi chay Docker Compose DEV! Vui long kiem tra Docker Desktop.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/3] Kiem tra trang thai cac container DEV:
docker compose -f docker-compose.yml ps

echo.
echo =====================================================================
echo               HA TANG DEV DOCKER DA SAN SANG!
echo =====================================================================
echo [CORE SERVICES]
echo * PostgreSQL + PostGIS:     localhost:5432 (du lieu giu nguyen)
echo * Redis Cache:              localhost:6379
echo * Keycloak IAM:             http://localhost:8080
echo * Mailpit Web Mailbox:      http://localhost:8025
echo.
echo [GATEWAY & OBSERVABILITY]
echo * NGINX Gateway:            http://localhost:8088
echo * Grafana Dashboard:        http://localhost:8088/grafana/ (qua Gateway)
echo                             hoac http://localhost:3000 (truc tiep)
echo * Prometheus Metrics:       http://localhost:9090
echo * Loki Logs Storage:        http://localhost:3100
echo * Grafana Alloy Collector:  http://localhost:12345
echo =====================================================================
echo.
echo Tiep theo ban co the:
echo   1. Chay Backend Spring Boot trong IDE o port 8081
echo      (Prometheus se tu dong scrape metrics qua /actuator/prometheus)
echo   2. Chay Frontend qua file: chargeops-frontend\start-frontend.bat
echo      (Khoi dong ca Web Console 5173 va Mobile App 8082)
echo   3. Mo Grafana http://localhost:3000 de xem dashboard giam sat he thong
echo.
echo Khi muon tat ha tang Dev, ban go: docker compose down (hoac chay stop-dev.bat)
echo =====================================================================
pause
