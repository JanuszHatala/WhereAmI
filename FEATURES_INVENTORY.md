# WhereIAm - Project Feature Inventory & Status Register

This document is the authoritative project register for all requested, implemented, planned, and modified features from the inception of the **WhereIAm** project. Every modification, addition, or architectural decision must update this file.

---

## 1. Summary Status Matrix

| Category | Total Features | Implemented & Verified | In Progress | Planned / Future |
| :--- | :---: | :---: | :---: | :---: |
| **Core Location & Geocoding** | 6 | 6 | 0 | 0 |
| **Android System Integration** | 4 | 4 | 0 | 0 |
| **Map & Viewport Engine** | 7 | 7 | 0 | 0 |
| **Trip Tracking & History** | 7 | 7 | 0 | 0 |
| **Saved Places ("My Places")** | 4 | 4 | 0 | 0 |
| **User Interface & Layout** | 6 | 6 | 0 | 0 |
| **Live Location Sharing (Self-Hosted/Cloud)** | 7 | 5 | 0 | 2 |
| **Diagnostics, Cache & CI/CD** | 6 | 6 | 0 | 0 |
| **Total** | **47** | **45** | **0** | **2** |

---

## 2. Detailed Feature Inventory

### Category 1: Core Location & Geocoding

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **LOC-01** | Multi-Tier Reverse Geocoding | Reverse geocodes coordinates via Android Geocoder (Tier 1) and OSM Nominatim (Tier 2) with fallback to last good location. | **Implemented** | Top Locality Card & Android Auto. Check `LocationManager.kt`. | Essential for offline/poor signal areas. |
| **LOC-02** | Administrative Hierarchy Formatting | Displays `gm.` (commune), `pow.` (county), and `woj.` (voivodeship) in Poland with smart deduplication; international fallback elsewhere. | **Implemented** | Subtitle line in Top Locality Card. Check `formatHierarchy()` in `LocationManager.kt`. | Polished in v1.1.0 to eliminate redundant city repetitions. |
| **LOC-03** | Border Hysteresis Filter | Prevents rapid ping-pong switching between adjacent municipalities when driving along borders or GPS jitter. | **Implemented** | Drive along border roads. Check `applyBorderHysteresis()` in `LocationManager.kt` & Telemetry Log. | Requires sustained distance/time before committing city change. |
| **LOC-04** | Street Name & House Number Display | Displays current street and house number prominently in Top Locality Card and Android Auto. | **Implemented** | Top Locality Card second line. Check `streetOrRoad` in `MainActivity.kt`. | Full-width dedicated line so long names never truncate. |
| **LOC-05** | Street & Number Smoothing (Anti-Jumping) | Stabilizes vehicle street identity against momentary side-street blips when passing intersections. | **Implemented** | Drive past crossroads at >15 km/h. Check `applyStreetHysteresis()` in `LocationManager.kt`. | Rejects 1-off side street geocode results during continuous driving. |
| **LOC-06** | Activity Profiles (Car, Cycling, MTB, Hiking, Run, Walk) | 6 dedicated activity profiles with custom speed vs. pace formatting, mountain elevation/peak triggers, and adaptive sampling. | **Implemented** | Settings -> Activity Profile selector, or quick pill tap on Top Card. | Car/Cycling/MTB display speed-first; Walk/Run/Hiking display pace-first. |

---

### Category 2: Android System Integration

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **SYS-01** | Android Auto Media Integration | Exposes location as an automotive media browser queue item with live metadata. | **Implemented** | Connect device to Android Auto. Check `AutoMediaService.kt`. | Allows passive, glanceable heads-up driving display. |
| **SYS-02** | Home Screen App Widget | Interactive home screen widget displaying locality, street, speed, and refresh/live toggle. | **Implemented** | Add WhereIAm 4x2 widget to Android launcher. Check `WhereIAmWidget.kt`. | Supports manual refresh and background push. |
| **SYS-03** | Foreground Service & WakeLock | Robust background tracking service preventing Android OS from terminating recording. | **Implemented** | Start trip, switch apps or turn off screen. Check `LiveTrackingService.kt`. | Uses `FOREGROUND_SERVICE_LOCATION` & `PARTIAL_WAKE_LOCK`. |
| **SYS-04** | Battery Optimization Exemption | Direct prompt allowing user to whitelist WhereIAm from OS battery killing. | **Implemented** | Settings -> Battery Exemption button. Check `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. | Critical for all-day hiking and background trip logging. |

---

### Category 3: Map & Viewport Engine

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **MAP-01** | OSM Vector Map Rendering | Native OpenStreetMap tile rendering with hardware/software fallback and custom styles. | **Implemented** | Main screen background. Check `OsmMapView.kt`. | 1:1 crisp tile scaling without DPI blur. |
| **MAP-02** | Multi-Orientation Cycle (Auto, N, E, S, W) | Orientation modes: Course-Up (Auto-rotates to GPS heading), North-Up, East, South, West. | **Implemented** | Tap orientation button on right map controls. Check `MapOrientationMode`. | Auto mode aligns direction of travel to top of screen. |
| **MAP-03** | GPS Marker Bearing Alignment Fix | Position marker arrow points strictly straight UP in Auto/Course-Up mode; points true heading relative to screen in cardinal modes. | **In Progress** | Drive with map in AUTO mode. Check `OsmMapView.kt` line 140-160. | Fixes OSMDroid `mFlat` inverted rotation bug. |
| **MAP-04** | Recenter & Auto-Follow | Map smoothly animates to follow current position; pauses on manual scroll, resumes after 8s. | **Implemented** | Scroll map -> wait 8s or tap Crosshairs button. Check `isFollowing` in `OsmMapView.kt`. | Ensures user can explore map freely without losing live lock. |
| **MAP-05** | City/Town Boundary Polygons (Phase 2) | Renders territorial administrative boundary polygons fetched from OSM Overpass. | **Implemented** | Settings -> "Show City/Town Borders" switch. Check `BoundaryHelper.kt`. | Simplifies geometry for 60fps rendering. |
| **MAP-06** | Traversal Road Heat Map | Visualizes aggregated routes of all recorded trips as glowing heat corridor polylines. | **Implemented** | Settings -> "Road & Path Heat Map" switch. Check `heatMapPolylines` in `OsmMapView.kt`. | Shows coverage of all roads ever driven/walked. |
| **MAP-07** | Map Offline Cache Management | View tile cache disk usage, clear stale tiles, and pre-cache bounding box for offline hiking. | **Implemented** | Settings -> "Offline Map Cache". Check `OsmMapView.kt` & `MainActivity.kt`. | 500MB maximum offline disk cache with zoom 17/18 coverage. |

---

### Category 4: Trip Tracking & History

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **TRP-01** | Manual Trip Recording (Pill) | Instant one-tap start/stop from bottom action bar with live elapsed distance/time. | **Implemented** | Bottom action bar `● REC` / `■ STOP` pill. Check `TripManager.kt`. | Streamlined capsule design in v1.2.3. |
| **TRP-02** | Automatic Trip Start / Stop | Automatically starts recording when moving above profile speed threshold; stops when stationary. | **Implemented** | Settings -> Recording Mode (Automatic). Check `checkAutoTripStart()`. | Stationary timeout configurable (1m, 3m, 5m, 10m). |
| **TRP-03** | Places & Trips Fullscreen Overlay | Dedicated fullscreen view for inspecting trip logs, GPX export, and saved places. | **Implemented** | Tap History clock button on bottom action bar. Check `showTripsSheet` in `MainActivity.kt`. | Fixed in v1.2.2 so no background widgets leak through. |
| **TRP-04** | Trip Filtering & Search | Filter trips by activity type, date range (Today, Week, Month, All), and name search; multi-select. | **Implemented** | Places & Trips -> Trips tab filters. Check `filteredTrips` in `MainActivity.kt`. | Easily manage and bulk export trips. |
| **TRP-05** | GPX Export | Export recorded trips to standard GPX files for Garmin, Strava, or Google Earth. | **Implemented** | Places & Trips -> Tap trip -> Export GPX. Check `GpxExporter.kt`. | FileProvider shares directly to external apps. |
| **TRP-06** | Realistic Cross-Trip Visit Counts | Journey transition model that filters stationary tests, intra-trip bounces, and duplicate arrivals. | **Implemented** | Places & Trips -> Statistics tab. Check `computeRealisticVisitCounts()`. | Accurately reflects real-world journeys. |
| **TRP-07** | Active Trip Visited Places Overlay | Tapping current locality name on top card opens timeline overlay of all places visited during active trip. | **In Progress** | Tap top locality label while recording. Check `ActiveTripRouteDialog`. | Real-time chronology of towns visited on current trip. |

---

### Category 5: Saved Places ("My Places")

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **PLC-01** | Save Location (Current or Tapped) | Save current location or any tapped map point with custom name, category, and radius. | **Implemented** | Bottom action bar Bookmark button or tap map pin. Check `SavedPlace.kt`. | Categories: Home, Work, Family, School, Favorite, Custom. |
| **PLC-02** | Custom Category Pins on Map | Saved places rendered on OSM map with category-specific emojis and colored pins. | **Implemented** | View saved places on main map. Check `makeSavedPlaceIcon()` in `OsmMapView.kt`. | Distinct colors per category. |
| **PLC-03** | Proximity Geofencing Alerts | Displays `[🏠 Home]` badge on top card when current position is within saved place radius. | **Implemented** | Arrive at saved place. Check `nearbySavedPlace` in `MainActivity.kt`. | Shows custom category badge next to city name. |
| **PLC-04** | Open in Google Maps / Navigate To | One-tap button to open any saved place, tapped pin, or current location in Google Maps. | **Implemented** | Top Card Maps icon or Saved Place card -> "Navigate". Check `openInGoogleMaps()`. | Directly launches Google Maps navigation. |

---

### Category 6: User Interface & Layout

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **UI-01** | Compact vs. Normal Locality Card | Toggle between full detailed card and compact 1-line view without losing street name. | **Implemented** | Tap Chevron on Top Card or toggle in Settings. Check `LocalityCardStyle`. | Both modes display full street name and hierarchy. |
| **UI-02** | Keep Screen On Toggle | One-tap eye button on bottom bar to prevent screen timeout while driving or hiking. | **Implemented** | Bottom bar leftmost Eye button. Check `keepScreenOn` in `MainActivity.kt`. | Sets `FLAG_KEEP_SCREEN_ON`. |
| **UI-03** | Responsive Bottom Action Bar | Responsive width constraint (`widthIn(max = 440.dp)`) fitting all 6 actions across any screen size. | **Implemented** | Check on device in portrait/landscape. Check `MainActivity.kt` lines 903-1037. | Fixed in v1.2.3 to eliminate screen clipping. |
| **UI-04** | Icon De-Duplication (Zero Redundancy) | Eliminates duplicate search, bookmark, and heatmap buttons across top card, bottom bar, and map controls. | **Implemented** | Verify all 3 screen control clusters. Check `MainActivity.kt` & `OsmMapView.kt`. | Each action has exactly one clear, dedicated home. |
| **UI-05** | Destination Search & Pin Card | Search any place or address worldwide via Nominatim with floating action card on map. | **Implemented** | Bottom bar Search button -> search address -> tap result. Check `SearchHelper.kt`. | Shows pin with navigation and save actions. |
| **UI-06** | Multi-Language Support (EN, PL, Native) | Live language switcher for place names and administrative regions. | **Implemented** | Settings -> Display Language. Check `DisplayLanguage` in `LocationManager.kt`. | Shows native spelling and selected language translation. |

---

### Category 7: Live Location Sharing (Self-Hosted / Cloud)

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **LIV-01** | Unique Session ID Generator (10-Char Slug) | Generates clean, human-friendly 10-char slug (e.g. `hike-7k9x2p` or `8m4p2z9t1q`) for sharing links. | **Implemented** | Live Share Dialog -> "New Session". Check `LiveSharingManager.kt`. | Cleaner and more readable than raw UUIDs. |
| **LIV-02** | Live Share Lifecycle (Pause, Extend, Permanent, Stop) | Full control over sharing session: Pause/Resume, Extend (+1h, +6h), Permanent ("∞ No limit"), remaining time countdown, and friendly stopped view. | **Implemented** | Top Locality Card utility row badge & Live Share Dialog. Check `LiveSharingManager.kt`. | Host can pause sharing temporarily without breaking the visitor link. |
| **LIV-03** | Battery-Efficient Offline Sync Queue | Accumulates GPS breadcrumbs in local SQLite queue when offline; flushes in batch on reconnect or interval (e.g. 5 min). | **Implemented** | Hike with mobile data off -> turn on data. Check `live_share_queue` in `TripDatabaseHelper.kt`. | Minimizes mobile radio wakeups to conserve battery. |
| **LIV-04** | Mountain & Trekking Environment Tags | Detects mountain peaks (`natural=peak`), passes (`mountain_pass`), elevations (`ele`), and trail colors (`route=hiking`). | **Implemented** | Walk/hike mode near mountain peaks. Check `TrekkingHelper.kt`. | Enriches live share view and top card with mountain names. |
| **LIV-05** | Live Visitor Analytics (Future) | Track live session visitor counts, duration viewed, and unique guest impressions. | **Planned (Future)** | Live Share Dashboard. Planned for future server update. | Privacy-friendly metrics without requiring guest accounts. |
| **LIV-06** | Live Visitor Interactive Engagement (Future) | Controlled, lightweight visitor communication (quick emoji reactions, status pings, simple 1-way/2-way check-ins). | **Planned (Future)** | Live Share Web Viewer & Mobile App. Planned for future update. | Allows family/friends to send cheerful reactions or check-ins. |
| **LIV-07** | Multi-Provider Live Sync (Synology, Local, GitHub) | Flexible destination endpoints supporting local dev server (`127.0.0.1:3003` via ADB reverse), Synology NAS Docker, or GitHub Pages. | **Implemented** | Live Share Dialog -> Share Target. Check `LiveShareProvider` enum. | Default port 3003 and clean `/live/:id` routing. |

---

### Category 8: Diagnostics, Cache & CI/CD

| ID | Feature Name | Description | Status | Verification & Where to Check | Decision History / Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **OPS-01** | Diagnostic Telemetry Logger & Export | In-memory ring buffer logging GPS fixes, hysteresis switches, and trip decisions with JSON export. | **Implemented** | Settings -> Export Telemetry. Check `TelemetryLogger.kt` & `file_paths.xml`. | Fixed crash caused by missing FileProvider cache-path. |
| **OPS-02** | Dockerized Live Share Web Service | Lightweight Node.js/Alpine container running on port 3003 serving clean `https://<domain>/live/:id` UI. | **Implemented** | Deploy container on Synology NAS or Docker host. Check `server/Dockerfile`. | Configurable port (default 3003) and clean URL routing. |
| **OPS-03** | GitHub Actions Docker CI/CD | GHA workflow automatically building and publishing Docker container to GitHub Container Registry (`ghcr.io`). | **Implemented** | Git push -> GitHub Actions. Check `.github/workflows/docker-publish.yml`. | Enables automatic zero-touch Synology container updates. |
| **OPS-04** | GitHub Actions Android APK Releases | GHA workflow automatically compiling release/debug APK and publishing downloadable release artifacts. | **Implemented** | Git tag / push -> GitHub Releases. Check `.github/workflows/build-apk.yml`. | Allows direct downloading and installing APK on any phone. |
| **OPS-05** | GitHub-Hosted Live Share Fallback | Direct GitHub Pages / Gist fallback viewer for live location sharing before Synology NAS is configured. | **Implemented** | Live Share Settings -> Provider -> GitHub Mode. Check `server/public/index.html`. | Zero-server required alternative. |
| **OPS-06** | Offline Map Cache Management | Disk size indicator, 1-tap cache clearance, and offline bounding region caching. | **Implemented** | Settings -> Offline Map Cache. Check `Configuration.getInstance().osmdroidTileCache` in `MainActivity.kt`. | Prevents unbounded disk growth while enabling full offline mapping. |

---

*Last Updated: 2026-09-10 (v1.3.1 Verified on Device & Local Server)*
