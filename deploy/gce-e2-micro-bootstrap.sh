#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/cx-dashboard-latest}"
REPO_URL="${REPO_URL:-https://github.com/atulanand16898/cx-dashboard-latest.git}"
BRANCH="${BRANCH:-main}"
SWAP_SIZE_GB="${SWAP_SIZE_GB:-2}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this script as root or with sudo."
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl git gnupg lsb-release

install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
fi

ARCH="$(dpkg --print-architecture)"
CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
cat >/etc/apt/sources.list.d/docker.list <<EOF
deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${CODENAME} stable
EOF

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker
systemctl start docker

if ! swapon --show | grep -q '/swapfile'; then
  fallocate -l "${SWAP_SIZE_GB}G" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p "$APP_DIR"
if [ ! -d "$APP_DIR/.git" ]; then
  git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
else
  git -C "$APP_DIR" fetch --all
  git -C "$APP_DIR" checkout "$BRANCH"
  git -C "$APP_DIR" reset --hard "origin/$BRANCH"
fi

if [ ! -f "$APP_DIR/.env.production" ]; then
  cp "$APP_DIR/.env.production.example" "$APP_DIR/.env.production"
  echo
  echo "Created $APP_DIR/.env.production from the example file."
  echo "Edit it with your production secrets before deploying."
fi

echo
echo "Bootstrap complete."
echo "Next steps:"
echo "1. Edit $APP_DIR/.env.production"
echo "2. Run: sudo APP_DIR=$APP_DIR BRANCH=$BRANCH $APP_DIR/deploy/gce-e2-micro-deploy.sh"
