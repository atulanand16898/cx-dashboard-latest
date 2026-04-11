# Google Cloud Free-Tier Deploy

This project can run on a Google Compute Engine always-free `e2-micro` VM by using Docker Compose.

## What this setup uses

- `1 x e2-micro` VM
- Ubuntu LTS
- Docker + Docker Compose
- Caddy on ports `80` and `443` for automatic HTTPS
- React frontend served internally behind Caddy
- Spring Boot backend on internal port `8081`
- PostgreSQL in Docker

## 1. Create the VM

In Google Cloud Console:

1. Create a project or pick an existing one.
2. Enable billing if Google asks.
3. Go to `Compute Engine`.
4. Create a VM with:
   - Machine type: `e2-micro`
   - Region: `us-central1`, `us-east1`, or `us-west1`
   - Boot disk: Ubuntu LTS
   - Firewall: allow `HTTP` and `HTTPS`
5. Reserve or note the external IP.

## 2. SSH into the VM

Use the Google Cloud browser SSH or local SSH.

## 3. Bootstrap the VM

Run:

```bash
curl -fsSL https://raw.githubusercontent.com/atulanand16898/cx-dashboard-latest/main/deploy/gce-e2-micro-bootstrap.sh -o /tmp/gce-bootstrap.sh
sudo APP_DIR=/opt/cx-dashboard-latest BRANCH=main bash /tmp/gce-bootstrap.sh
```

This script:

- installs Docker
- installs Docker Compose
- creates swap for the small VM
- clones the repo
- creates `.env.production` if missing

## 4. Fill in production secrets

Edit:

```bash
sudo nano /opt/cx-dashboard-latest/.env.production
```

Set at least:

```env
POSTGRES_DB=cxalloydb
DB_USERNAME=cxalloy
DB_PASSWORD=change-me
APP_DOMAIN=YOUR_DOMAIN
CORS_ALLOWED_ORIGINS=https://YOUR_DOMAIN,https://www.YOUR_DOMAIN
CXALLOY_API_IDENTIFIER=replace-me
CXALLOY_API_SECRET=replace-me
FACILITYGRID_API_CLIENT_ID=
FACILITYGRID_API_CLIENT_SECRET=
JWT_SECRET=replace-me-with-a-long-random-secret
OPENAI_API_KEY=
OPENAI_DEFAULT_MODEL=gpt-5.4
APP_FILES_STORAGE_DIR=/tmp/project-files
APP_LOG_LEVEL=INFO
DB_POOL_MAX_SIZE=6
JAVA_TOOL_OPTIONS=-XX:MaxRAM=384m -XX:InitialRAMPercentage=20 -XX:+UseSerialGC
```

Point your DNS to the VM before deploying:

```text
A     @       YOUR_VM_IP
CNAME www     @
```

For GoDaddy, add those records in the DNS manager and remove conflicting forwarding or parking records.
Once DNS is live and ports `80` and `443` are open, Caddy will request and renew the HTTPS certificate automatically.
The `project_files_data` Docker volume keeps Files-tab uploads and AI file-library context across container rebuilds.

## 5. Deploy

Run:

```bash
sudo APP_DIR=/opt/cx-dashboard-latest BRANCH=main /opt/cx-dashboard-latest/deploy/gce-e2-micro-deploy.sh
```

## 6. Open the app

Visit:

```text
https://YOUR_DOMAIN
```

## 7. Update the app later

After pushing new commits to GitHub:

```bash
cd /opt/cx-dashboard-latest
sudo APP_DIR=/opt/cx-dashboard-latest BRANCH=main ./deploy/gce-e2-micro-deploy.sh
```

## Notes

- This is the simplest low-cost deployment for the current stack.
- `e2-micro` is small, so first builds may take a few minutes.
- The swap file is intentional to help the Maven and frontend image builds complete on the tiny VM.
- Long-term, if usage grows, move PostgreSQL off the VM first.
