@echo off
setlocal
for %%I in ("%~dp0..\..") do set "BACKEND_DIR=%%~fI"
cd /d "%BACKEND_DIR%"
title ChargeOps - Stop public demo
if not exist ".env" (echo [ERROR] Backend .env is missing.& exit /b 1)
if not exist ".env.cloudflared" (echo [ERROR] .env.cloudflared is missing.& exit /b 1)
set "COMPOSE_FILES=--env-file .env --env-file .env.cloudflared -f docker-compose.demo.yml -f compose.domain.yml -f compose.domain-demo.yml -f compose.mail-hostinger.yml"
echo Stopping demo containers and Cloudflare Tunnel. Named Docker volumes and data are kept.
docker compose %COMPOSE_FILES% --profile public down --remove-orphans
if errorlevel 1 exit /b %ERRORLEVEL%
echo Demo Docker services stopped. Stop the Expo driver web server with Ctrl+C in its window.
