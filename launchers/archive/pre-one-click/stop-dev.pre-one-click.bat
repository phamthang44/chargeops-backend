@echo off
title ChargeOps Dev Environment Shutdown
echo =====================================================================
echo                CHARGEOPS - DUNG HA TANG DEV
echo =====================================================================
echo.
echo Dang tat cac container Docker Dev...
docker compose -f docker-compose.yml down

echo.
echo =====================================================================
echo               DA DUNG HA TANG DEV THANH CONG!
echo =====================================================================
pause
