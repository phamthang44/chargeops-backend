@echo off
title ChargeOps Demo Shutdown
echo =====================================================================
echo                CHARGEOPS - DUNG HE THONG DEMO
echo =====================================================================
echo.
echo [1/2] Dang tat Tailscale Funnel...
tailscale funnel reset >nul 2>&1

echo.
echo [2/2] Dang tat cac Docker container demo...
docker compose -f docker-compose.demo.yml down

echo.
echo =====================================================================
echo             DA DUNG HE THONG DEMO THANH CONG!
echo =====================================================================
pause
