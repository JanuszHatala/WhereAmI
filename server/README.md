# WhereAmI - Live Location Sharing Server

Self-hosted Node.js live location tracking server backed by PostgreSQL 17 + PostGIS with Leaflet web viewer.

Runs on **Synology NAS (Container Manager)**, Linux VPS, or developer machines.

---

## Features

- **Port 3003**: Runs on port 3003 by default.
- **PostgreSQL 17 + PostGIS**: Normalized storage for sessions, segmented points (with geography Point), guest viewers, and view events.
- **Clean URLs**: Clean viewer endpoint at `/live/:id` (e.g. `https://my-nas.synology.me:3003/live/hike-7k9x2p`).
- **REST Telemetry API**:
  - `POST /api/sessions/:id`: Initialize/update session metadata; returns `writeToken`.
  - `POST /api/sessions/:id/points`: Append GPS fix, speed, altitude, battery, places visited.
  - `GET /api/sessions/:id`: Retrieve live session metadata and telemetry history.
  - `POST /api/sessions/:id/status`: Toggle pause states.
  - `POST /api/sessions/:id/extend`: Extend session expiration by `+N` hours.
  - `POST /api/sessions/:id/end`: Terminate sharing.
- **Nightly Logical Backups**: Automated dump sidecar via `prodrigestivill/postgres-backup-local:17` with 7 daily, 4 weekly, and 6 monthly rotations.

---

## 1. Quick Start (Local Development)

```bash
cd server
npm install
docker compose up -d db
npm test
npm start
```

Viewer is available at: `http://localhost:3003/live/<session-id>`  
Health check: `http://localhost:3003/health`

---

## 2. Docker Compose (Local Stack)

Run from `server/`:

```bash
docker compose up -d --build
```

---

## 3. Production Deployment (Synology NAS)

### Architecture
- `app`: Node.js 20 service on port 3003 (updated automatically by Watchtower).
- `db`: PostgreSQL 17 + PostGIS (`postgis/postgis:17-3.5`), pinned, internal network only.
- `db-backup`: Logical backups container writing to `/volume1/docker/whereami/backups`.
- `watchtower`: Auto-updates only `app`.

### Pre-Deployment Steps on Synology NAS
1. SSH into Synology NAS:
   ```bash
   sudo mkdir -p /volume1/docker/whereami/postgres /volume1/docker/whereami/backups
   sudo chown -R 999:999 /volume1/docker/whereami/backups
   ```
2. Generate secure password for PostgreSQL:
   ```bash
   openssl rand -base64 32 | tr -d '/+='
   ```
3. Update `.env` in the Container Manager project folder:
   ```env
   POSTGRES_PASSWORD=<generated_password>
   ```
4. Configure Synology Hyper Backup:
   - Select `/volume1/docker/whereami/backups` as source folder.
   - Do NOT backup active `/volume1/docker/whereami/postgres` directory directly to avoid database inconsistency.

### Backup & Restore Runbook
- **Create manual backup now**:
  ```bash
  docker exec -it whereami-db-backup /backup.sh
  ```
- **Restore database from dump**:
  ```bash
  gunzip -c /volume1/docker/whereami/backups/daily/whereami-YYYY-MM-DD.sql.gz | docker exec -i whereami-db psql -U whereami -d whereami
  ```
