# Where Am I - Backend Architecture & Live Sharing Design

This document details the future architecture for the private self-hosted backend on Synology NAS, Cloudflare Tunnel integration, battery-optimized live tracking (especially for hiking), and local offline queueing.

---

## 1. System Overview

```
[ Android Device (Where Am I) ]
        │
        ├── (Online)  ──────> Cloudflare Tunnel ──> [ Synology NAS (Docker API) ]
        │                                                     │
        └── (Offline) ──> [ Local SQLite Queue ]              ├── SQLite / PostgreSQL Database
                                  │ (Syncs when online)       └── Lightweight Web Dashboard (Leaflet / OSM)
                                  ▼                                    │
                       [ Public / Shared Live View ] <─────────────────┘
```

---

## 2. Low-Bandwidth & Battery-Saving Modes

### A. Profiles & Transmission Intervals

| Profile | Transmission Interval | Accuracy | Payload Size | Primary Optimization |
| :--- | :--- | :--- | :--- | :--- |
| **🚗 Driving** | Every 5–10 seconds | High (GPS) | ~150 bytes | Real-time smoothness, live speed |
| **🚴 Cycling** | Every 15–30 seconds | High (GPS) | ~120 bytes | Balance of battery and route accuracy |
| **🚶 Hiking / Walking** | Every 60–180 seconds | Balanced / Low-power | ~90 bytes | Extreme battery life, low-signal resilience |

### B. Ultra-Compact Payload Format (JSON or Protobuf)
For hiking over weak EDGE / 2G / 3G connections, packets must be minimal:

```json
{
  "t": 1725801234,      // Unix timestamp
  "lat": 50.81234,      // Latitude (5 decimals ~ 1.1m precision)
  "lng": 19.12345,      // Longitude
  "spd": 4.2,           // Speed km/h
  "alt": 284,           // Altitude meters
  "bat": 87,            // Battery percentage
  "plc": "Kłomnice",    // Current locality
  "str": "DK91"         // Current road/street (optional)
}
```
*Gzip/Deflate compression on HTTP headers reduces this to under **100 bytes per ping**.*

---

## 3. Offline Queue & Resilient Synchronization

When hiking or driving through dead zones (no cellular coverage):
1. **Local Persistent Queue:** 
   - Every unsent location fix is appended to an internal SQLite table `sync_queue (id, payload, created_at, retry_count)`.
2. **Network State Observer:**
   - Uses Android `ConnectivityManager.NetworkCallback`.
   - When connection returns, a background worker (`WorkManager` or coroutine worker) batches up to 50 queued points in a single HTTP POST request (`/api/v1/sync/batch`).
3. **Zero Data Loss:**
   - Complete route continuity is preserved on the server even after hours without signal.

---

## 4. Synology NAS Backend Setup

### A. Tech Stack
* **Container:** Python (FastAPI) or Go (Single lightweight binary).
* **RAM Footprint:** < 30 MB on Synology DSM.
* **Database:** Embedded SQLite (zero maintenance) or PostgreSQL with PostGIS.
* **Network:** Cloudflare Tunnel (`cloudflared` Docker container on NAS) -> No port forwarding or public IP exposure required on home router.

### B. Endpoints
* `POST /api/v1/location/ping`: Authenticated with device token.
* `POST /api/v1/location/batch`: Syncs offline queued fixes.
* `GET /live/{share_token}`: Public or password-protected web viewer for family/friends.
* `GET /api/v1/trips`: Export and browse past trips.

---

## 5. Privacy & Access Controls

1. **Unique Shareable Link:**
   - Expiring tokens (e.g. valid for 4 hours, 24 hours, or permanent for family).
   - Read-only web map showing live marker, speed badge, direction, and route history.
2. **Emergency Ping:**
   - Manual button to send an instant ping with battery status and exact coordinates to designated contacts via SMS / Webhook.
