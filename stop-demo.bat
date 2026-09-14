@echo off
title ChargeOps Demo Shutdown
echo =====================================================================
echo                CHARGEOPS - DUNG HE THONG DEMO
echo =====================================================================
echo.
echo [1/2] Dang tat Tailscale Funnel...
tailscale funnel --bg http://127.0.0.1:8080 off
tailscale funnel --bg --set-path /api http://127.0.0.1:8081/api off
tailscale funnel --bg --https=8443 http://localhost:5173 off

echo.
echo [2/2] Dang tat cac Docker container demo...
docker compose -f docker-compose.demo.yml down

echo.
echo =====================================================================
echo             DA DUNG HE THONG DEMO THANH CONG!
echo =====================================================================
pause
