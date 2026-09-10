# WhereAmI (Android & Live Tracking Web Viewer)

**WhereAmI** is a location-awareness companion app for Android and Android Auto, paired with an optional self-hosted Live Location Sharing web service (designed for Synology NAS / Docker).

Whether driving, mountain biking, or trekking in alpine environments, WhereAmI provides immediate locality recognition, territorial administrative hierarchies, smart address smoothing, road coverage heatmaps, and battery-friendly live tracking.

---

## Key Capabilities

- 📍 **Instant Multi-Tier Geocoding**: Dual-tier fallback (Android Geocoder + OSM Nominatim) identifying city, commune (`gm.`), county (`pow.`), voivodeship (`woj.`), and country.
- 🚗 **Android Auto Integration**: Broadcasts location and destination updates to car heads-up infotainment screens via Automotive Media Browser.
- 🥾 **Activity Profiles**:
  - `🚗 Driving`: Speed-first formatting, road hysteresis, high-speed sampling.
  - `🚴 Cycling`: Speed-first formatting, cycling cadence.
  - `🚵 MTB`: Mountain bike profile tailored for technical trails and forest paths.
  - `🥾 Hiking`: Pace-first formatting, mountain peak elevation detection, battery-optimized breadcrumbs.
  - `🏃 Running`: Pace-first formatting, stride interval logging.
  - `🚶 Walking`: Urban/pedestrian pace smoothing.
- 📡 **Self-Hosted Live Location Sharing**:
  - Share a clean link (`https://live.yourdomain.com/live/<slug>`) with family/friends.
  - Supports **Synology NAS (Docker)**, **Local Test Server (Port 3003)**, and **GitHub Pages**.
  - Real-time Leaflet viewer showing breadcrumb trail, speed, altitude, battery, and mountain peaks.
  - Controls: Pause/Resume sharing, session extension (`+1h`, `+6h`, or `∞ No limit`), and peaceful ended state (not 404).
- 🗺️ **Comprehensive Map & Offline Cache**:
  - OpenStreetMap vector rendering with Auto-Follow and Course-Up orientation.
  - Road traversal heat map visualizing all past recorded journeys.
  - Offline tile disk cache configurable up to 500 MB with bounding box pre-caching.
- 💾 **Telemetry Diagnostics & GPX Export**:
  - In-memory diagnostic ring buffer exportable as JSON.
  - 1-tap GPX export for Garmin, Strava, and Google Earth.

---

## Repository Structure

```
WhereIAm/
├── app/                  # Android Kotlin application (Jetpack Compose, OSMDroid)
├── server/               # Self-hosted Node.js / Docker live sharing service
│   ├── Dockerfile        # Container image definition
│   ├── docker-compose.yml# Synology Container Manager configuration
│   ├── server.js         # REST telemetry API & session persistence
│   ├── public/           # Responsive OpenStreetMap Leaflet viewer
│   └── README.md         # Synology NAS & Docker deployment guide
├── FEATURES_INVENTORY.md # Authoritative feature register & status matrix
└── README.md             # Project overview & quick start
```

---

## Live Location Server — Docker Deployment

The live tracking backend is a Node.js service packaged as a Docker image and published to the **GitHub Container Registry (GHCR)**.

### Image

```
ghcr.io/januszhatala/whereami-live:latest
```

> The image exposes **port 3003** internally. Map it to whatever external port you prefer (e.g. 80 or a reverse proxy upstream port).

---

### Option A — `docker run`

```bash
docker run -d \
  --name whereami-live \
  --restart unless-stopped \
  -p 3003:3003 \
  -v whereami-data:/app/data \
  ghcr.io/januszhatala/whereami-live:latest
```

| Flag | Purpose |
|---|---|
| `-p 3003:3003` | Expose port 3003. Change the left side to use a different host port, e.g. `-p 80:3003` |
| `-v whereami-data:/app/data` | Persistent volume for `sessions.json` (keeps data across restarts) |

---

### Option B — Docker Compose

```yaml
version: "3.8"
services:
  whereami-live:
    image: ghcr.io/januszhatala/whereami-live:latest
    container_name: whereami-live
    restart: unless-stopped
    ports:
      - "3003:3003"        # Change left side to remap the host port
    volumes:
      - whereami-data:/app/data

volumes:
  whereami-data:
```

Start with:
```bash
docker compose up -d
```

---

### Visitor View

Once running, visitors open the **GitHub Pages viewer** at:

```
https://januszhatala.github.io/WhereAmI/live/?id=<SESSION_ID>&server=<YOUR_SERVER_URL>
```

The shared link from the WhereAmI Android app (with **🌐 Public Server** provider) already encodes the correct `server=` URL automatically.

---

### Changing the Exposed Port

Only the left (host) side of the `-p` mapping needs changing. The container always listens on **3003** internally:

```bash
# Expose on host port 8080 instead:
-p 8080:3003
```

If using a Cloudflare Tunnel or nginx reverse proxy, you can bind to any internal port and let the proxy expose it publicly (as used at `https://whereami.janush.tech`).

---

### Updating to a New Version

```bash
# Pull the latest image
docker pull ghcr.io/januszhatala/whereami-live:latest

# Restart the container with the new image
docker compose pull && docker compose up -d
# — or with docker run —
docker stop whereami-live && docker rm whereami-live
docker run -d ... ghcr.io/januszhatala/whereami-live:latest
```

Sessions data is preserved in the named volume and survives updates.

---

For full Synology NAS / Container Manager deployment steps, see [server/README.md](server/README.md).

---

## Building the Android App

Prerequisites:
- Android SDK 34+
- Java JDK 17 or 21
- Gradle 9+ (included wrapper)

```bash
# Compile and build debug APK
.\gradlew.bat assembleDebug

# Install directly to connected Android device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
