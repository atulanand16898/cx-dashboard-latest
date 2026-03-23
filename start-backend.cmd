@echo off
setlocal

cd /d "%~dp0backend"

if not exist ".m2repo" mkdir ".m2repo"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$envFile = Join-Path (Resolve-Path '..').Path '.env.backend.local';" ^
  "if (Test-Path $envFile) {" ^
  "  Get-Content $envFile | ForEach-Object {" ^
  "    if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {" ^
  "      [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')" ^
  "    }" ^
  "  }" ^
  "}" ^
  "$env:SERVER_PORT = '8081';" ^
  "& mvn '-Dmaven.repo.local=.m2repo' 'spring-boot:run'"

