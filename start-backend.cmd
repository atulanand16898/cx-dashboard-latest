@echo off
setlocal

cd /d "%~dp0backend"

if not exist ".m2repo" mkdir ".m2repo"

set "SERVER_PORT=8081"
call mvn -Dmaven.repo.local=.m2repo spring-boot:run

