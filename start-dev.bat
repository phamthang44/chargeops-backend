@echo off
title ChargeOps Dev Environment Launcher
echo =====================================================================
echo                CHARGEOPS - KHOI DONG MOI TRUONG DEV
echo =====================================================================
echo.
echo [1/3] Don dep cac container demo (neu co)...
docker compose -f docker-compose.demo.yml down 2>nul

echo.
echo [2/3] Khoi dong cac container ha tang DEV (Postgres, Redis, Keycloak, Mailpit)...
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
echo * PostgreSQL + PostGIS:     localhost:5432 (du lieu giu nguyen)
echo * Redis:                    localhost:6379
echo * Keycloak IAM:             http://localhost:8080
echo * Mailpit Web Mailbox:      http://localhost:8025
echo =====================================================================
echo.
echo Tiep theo ban co the:
echo   1. Chay Backend Spring Boot trong IDE (IntelliJ / Eclipse / VS Code) o port 8081
echo   2. Chay Frontend qua file: chargeops-frontend\start-frontend.bat
echo      (Khoi dong ca Web Console 5173 va Mobile App 8082)
echo.
echo Khi muon tat ha tang Dev, ban go: docker compose down
echo =====================================================================
pause
