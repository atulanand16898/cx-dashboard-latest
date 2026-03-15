# CxAlloy Project Track — Frontend

A modern React dashboard for the CxAlloy Spring Boot integration backend.

## Tech Stack

- **React 18** + Vite
- **Tailwind CSS** (WHOOP-inspired dark aesthetic)
- **Recharts** — charts & visualizations
- **React Router 6** — routing
- **Axios** — API client with auto JWT refresh
- **react-hot-toast** — notifications
- **Lucide React** — icons

## Setup

```bash
npm install
npm run dev
```

App runs on http://localhost:5173

> **Backend** must be running on http://localhost:8080 (Vite proxy is pre-configured).

## Default Credentials

```
Username: admin
Password: admin123
```

## Features

| Page | Route | Description |
|------|-------|-------------|
| Overview | `/dashboard` | Project health, completion donuts, issue trend chart |
| Tags & Issues | `/issues` | CRUD + sync issues, search/filter |
| Tasks | `/tasks` | View & sync tasks |
| Checklists | `/checklists` | View & sync checklists |
| Assets | `/assets` | Equipment, buildings, systems |
| People | `/persons` | Project personnel |
| Companies | `/companies` | Organizations |
| Roles | `/roles` | Roles & responsibilities |
| Sync Center | `/sync` | Individual syncs, full background sync, diagnostics |

## Backend Endpoints Used

```
POST /api/auth/login
POST /api/auth/logout
POST /api/auth/refresh
GET  /api/auth/me

GET  /api/projects
GET  /api/projects/ids
POST /api/projects/sync
POST /api/projects/sync/{id}

GET  /api/issues?projectId=X
POST /api/issues
PUT  /api/issues/{id}
DELETE /api/issues/{id}
POST /api/issues/sync

GET  /api/tasks?projectId=X
POST /api/tasks/sync

GET  /api/checklists?projectId=X
POST /api/checklists/sync

GET  /api/assets?projectId=X
POST /api/assets/sync

GET  /api/persons?projectId=X
GET  /api/companies?projectId=X
GET  /api/roles?projectId=X

POST /api/sync/all
GET  /api/sync/status
GET  /api/sync/stats
POST /api/sync/issues|tasks|checklists|persons|companies|roles|assets
POST /api/sync/project/{id}
GET  /api/sync/raw-preview
GET  /api/sync/discover
```
