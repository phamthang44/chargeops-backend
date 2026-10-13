@echo off
setlocal
cd /d "%~dp0"
docker compose --env-file .env -f docker-compose.demo.yml -f compose.domain.yml -f compose.domain-demo.yml up --build -d --wait --wait-timeout 300
if errorlevel 1 exit /b 1
echo Full-stack containers ready. Start Driver Expo Web on port 8082 separately.
echo Portal: https://chargeops.online
echo Run start-cloudflare-tunnel.bat demo after DNS and Tunnel setup.
