@echo off
setlocal
cd /d "%~dp0"
rem Infra for IDE backend + frontend dev servers. This never invokes Tailscale.
docker compose --env-file .env -f docker-compose.yml -f compose.domain.yml up -d postgres redis elasticsearch keycloak mailpit grafana gateway
if errorlevel 1 exit /b 1
echo Infra ready. Start the backend in your IDE and frontend dev servers.
echo Portal: https://chargeops.online
echo Driver web: https://driver.chargeops.online
echo Run start-cloudflare-tunnel.bat after DNS and Tunnel setup are complete.
