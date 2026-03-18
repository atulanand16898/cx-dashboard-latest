@echo off
setlocal

cd /d "%~dp0backend"
docker compose up --build -d

