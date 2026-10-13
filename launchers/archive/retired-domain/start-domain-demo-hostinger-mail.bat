@echo off
setlocal
cd /d "%~dp0"
set "COMPOSE_FILES=--env-file .env -f docker-compose.demo.yml -f compose.domain.yml -f compose.domain-demo.yml -f compose.mail-hostinger.yml"
docker compose %COMPOSE_FILES% config --quiet
if errorlevel 1 exit /b 1
docker compose %COMPOSE_FILES% up -d --wait --wait-timeout 180 kafka
if errorlevel 1 exit /b 1
docker compose %COMPOSE_FILES% up -d --no-deps --force-recreate --wait --wait-timeout 180 backend
if errorlevel 1 exit /b 1
echo Backend is using Hostinger SMTP. Send one real app notification and confirm it arrives before stopping Mailpit.
echo Mailpit remains running until delivery is verified. After verification, stop it with: docker stop chargeops-mailpit-demo
