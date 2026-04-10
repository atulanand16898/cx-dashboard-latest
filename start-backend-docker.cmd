@echo off
setlocal

cd /d "%~dp0backend"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$envFile = Join-Path (Resolve-Path '..').Path '.env.backend.local';" ^
  "if (Test-Path $envFile) {" ^
  "  Get-Content $envFile | ForEach-Object {" ^
  "    if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {" ^
  "      [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')" ^
  "    }" ^
  "  }" ^
  "}" ^
  "& docker compose up --build -d"

