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

## Live Location Server Setup

For complete instructions on deploying the live location sharing service to your **Synology NAS** via Container Manager or Docker Compose, see [server/README.md](file:///c:/DevWorkspaces/jh/ProjektyIT/Android/WhereIAm/server/README.md).

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
