@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
cd /d "%BACKEND_DIR%"
if not exist .env.cloudflared (
  echo Create .env.cloudflared with CLOUDFLARE_TUNNEL_TOKEN from your Cloudflare Tunnel.
  exit /b 1
)
findstr /r "^CLOUDFLARE_TUNNEL_TOKEN=." .env.cloudflared >nul
if errorlevel 1 (
  echo CLOUDFLARE_TUNNEL_TOKEN is blank. Configure the Tunnel in Cloudflare first.
  exit /b 1
)
set "BASE=docker-compose.yml"
if /i "%~1"=="demo" set "BASE=docker-compose.demo.yml"
docker compose --env-file .env --env-file .env.cloudflared -f "%BASE%" -f compose.domain.yml --profile public up -d --no-deps cloudflared
if errorlevel 1 exit /b 1
echo Tunnel connector started. Check tunnel Healthy status in Cloudflare.
