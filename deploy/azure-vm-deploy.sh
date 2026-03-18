#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/project-aries}"
BRANCH="${BRANCH:-main}"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required on the VM"
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is required on the VM"
  exit 1
fi

mkdir -p "$APP_DIR"
cd "$APP_DIR"

if [ ! -d .git ]; then
  echo "Repository is not present in $APP_DIR"
  exit 1
fi

if [ ! -f .env.production ]; then
  echo ".env.production is missing in $APP_DIR"
  exit 1
fi

git fetch --all
git checkout "$BRANCH"
git reset --hard "origin/$BRANCH"

docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
docker image prune -f
