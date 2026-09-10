# WhereAmI - Live Location Sharing Server

Lightweight, self-hosted Node.js / Alpine live location tracking server with interactive OpenStreetMap (Leaflet) web viewer.

Designed to run seamlessly as a Docker container on **Synology NAS (Container Manager)**, Linux VPS, or local developer workstations.

---

## Features

- **Port 3003**: Runs on port 3003 by default.
- **Clean URLs**: Clean viewer endpoint at `/live/:id` (e.g. `https://my-nas.synology.me:3003/live/hike-7k9x2p`).
- **REST Telemetry API**:
  - `POST /api/sessions/:id/update`: Upload GPS fix, speed, altitude, battery, places visited, and mountain peaks.
  - `GET /api/sessions/:id`: Retrieve live session metadata and full telemetry history.
  - `POST /api/sessions/:id/status`: Toggle `isPaused` state.
  - `POST /api/sessions/:id/extend`: Extend session expiration by `+N` hours.
  - `POST /api/sessions/:id/end`: Terminate sharing and mark session as ended.
- **Disk Persistence**: Session records are persisted to `./sessions.json` so active sessions survive container restarts.
- **Informative Status States**:
  - `LIVE`: Green live tracking badge with pulsing marker, auto-centering, and remaining duration countdown (`⏳ 5h 22m left`).
  - `PAUSED`: Amber banner explaining the host has paused sharing.
  - `ENDED`: Red banner displaying final hike summary (distance, places visited, mountain peaks) rather than a 404 error page.

---

## 1. Quick Start (Local Development)

```bash
# Enter server directory
cd server

# Install dependencies
npm install

# Run locally on port 3003
npm start
```

Viewer is available at: `http://localhost:3003/live/<session-id>`  
Health check: `http://localhost:3003/health`

---

## 2. Docker & Docker Compose

### Using Docker Compose (Recommended)

Run from the `server` directory:

```bash
docker compose up -d --build
```

`docker-compose.yml`:
```yaml
version: "3.8"
services:
  whereami-live:
    build: .
    container_name: whereami-live
    restart: unless-stopped
    ports:
      - "3003:3003"
    environment:
      - PORT=3003
    volumes:
      - ./sessions.json:/app/sessions.json
```

### Manual Docker Build

```bash
docker build -t whereami-live:latest .
docker run -d --name whereami-live -p 3003:3003 --restart unless-stopped whereami-live:latest
```

---

## 3. Synology NAS Deployment (Container Manager)

1. **Open Container Manager** on DSM (DSM 7.2+).
2. **Create Project**:
   - Go to **Project** -> **Create**.
   - Project Name: `whereami-live`
   - Path: Choose a folder in `/docker/whereami-live` (upload `server/` files or use `docker-compose.yml`).
   - Source: Upload `docker-compose.yml`.
3. **Port Settings**:
   - Local Port: `3003` -> Container Port: `3003`.
4. **Reverse Proxy (HTTPS & Custom Domain)**:
   - Go to Synology **Control Panel** -> **Login Portal** -> **Advanced** -> **Reverse Proxy**.
   - Click **Create**:
     - Source: Protocol `HTTPS`, Hostname: `live.yourdomain.com` (or `your-nas.synology.me`), Port: `443`
     - Destination: Protocol `HTTP`, Hostname: `localhost`, Port: `3003`
   - Under **Custom Header**, ensure WebSocket support is enabled if needed.
   - Assign your Let's Encrypt SSL certificate in **Security** -> **Certificate**.
5. **Phone Configuration**:
   - In WhereAmI mobile app: Settings -> **Live Location Sharing**.
   - Provider: **Synology NAS**.
   - Server URL: `https://live.yourdomain.com` (or `http://<nas-ip>:3003`).

---

## 4. Local Testing with Phone via USB (ADB Reverse)

When testing locally without deploying to a public server:
1. Connect your Android phone to your PC via USB with USB Debugging enabled.
2. Run port forwarding:
   ```bash
   adb reverse tcp:3003 tcp:3003
   ```
3. In WhereAmI mobile app, select **Local Test Server** provider. The app will communicate with `http://127.0.0.1:3003`, which forwards directly to port 3003 on your PC.
4. On your PC browser, open `http://localhost:3003/live/<session-slug>`.
