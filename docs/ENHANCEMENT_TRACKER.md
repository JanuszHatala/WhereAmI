# WhereAmI - Enhancement Tracker & Progress Register

This document is the persistent, cross-session tracking register for the 24 feedback items and feature enhancements reported from field testing on 2026-09-11 and 2026-09-14.
It is updated after every phase to maintain full traceability across agent invocations.

---

## 1. Overall Progress Matrix

| Phase | Description | Total Items | Completed | In Progress | Pending |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Phase 1** | Urgent UI/UX & Responsive Layout Fixes | 6 | 6 | 0 | 0 |
| **Phase 2** | Geocoding Stability, Road Names & Boundaries | 7 | 7 | 0 | 0 |
| **Phase 3** | Map Controls, Overlays & Stationary Bearing | 5 | 5 | 0 | 0 |
| **Phase 4** | Live Sharing Power-Up & Web Viewer | 6 | 6 | 0 | 0 |
| **Backlog** | Platform Features Inventory Backlog | 4 | 0 | 0 | 4 |
| **Total** | | **28** | **24** | **0** | **4** |

---

## 2. Phase 1: Urgent UI/UX & Responsive Layout Fixes

| Item ID | User Request / Description | Target Components / Files | Status | Verification & Evidence |
| :--- | :--- | :--- | :---: | :--- |
| **UI-R01** | Fix non-responsive Current Trip Route places list where distance column is squashed vertically character-by-character (Screenshots 083026, 083106). | `MainActivity.kt` (`CurrentRouteDialog`) | **Completed** | Place name & subtitle now use `Modifier.weight(1f, fill = false)` with `TextOverflow.Ellipsis`; distance and time use `softWrap = false` and intrinsic widths. Verified by successful compilation. |
| **UI-R02** | Prevent location card overflow when place name is long ("Bystra Podhalańska", "Maków Podhalański") which squashes the `LIVE` pill into `LI \n VE` and overlaps actions (Screenshots 091618, 085847). | `MainActivity.kt` (`LocalityCard`) | **Completed** | Locality title row uses `weight(1f, fill = false)` with ellipsis; `LIVE`/`PAUSED` badge uses `softWrap = false`. |
| **UI-R03** | Consolidate Country into the location structure subtitle (`gm. ... • pow. ... • woj. ... • Polska`) to clean up top row space (Screenshot 091623). | `MainActivity.kt` (`LocalityCard`) | **Completed** | In both compact and normal modes, country is consolidated into the hierarchy row; top row reserved exclusively for badges and action buttons. |
| **UI-R04** | Landscape horizontal split-screen layout: details panel on left (38%), full interactive map on right (62%) to eliminate viewport obscuration (Item 13). | `MainActivity.kt` (`LocationScreen`) | **Completed** | Implemented responsive layout using `LocalConfiguration.current.orientation`: in landscape, a dedicated left column (38%) hosts LocalityCard, DestinationCard, and BottomControlsCard, leaving 62% for unobstructed full-height map. |
| **UI-R05** | Reorder Places & Trips dialog tabs so "Trips" is the first tab (`Trips (N) \| Places (N) \| Stats`) (Item 20). | `MainActivity.kt` (`PlacesTripsDialog`) | **Completed** | Swapped Tab 0 and Tab 1 so Trips is tab index 0 (selected by default) and Places is tab index 1. |
| **UI-R06** | Fix Merged Trips title formatting from `10 Sept 2026 - \n Merged Trip (3)` to clean standard format (Item 19, Screenshot 101553), and remove redundant panel share button (Item 24). | `TripDatabaseHelper.kt`, `MainActivity.kt` | **Completed** | Formatted merged title as `dd MMM yyyy, HH:mm • Merged Trips (N)` without trailing hyphens; removed redundant `Share` icon from LocalityCard header in favor of the map's floating action button. |

---

## 3. Phase 2: Geocoding Stability, Road Names & Boundaries

| Item ID | User Request / Description | Target Components / Files | Status | Verification & Evidence |
| :--- | :--- | :--- | :---: | :--- |
| **GEO-01** | Viaduct / Bridge trajectory inertia: prevent street name jumping between overpass and road below (Item 2, 11). | `LocationManager.kt` (`applyStreetHysteresis`) | **Completed** | When speed > 35 km/h on a major road (`isMajorRoad`), requires at least 5 consecutive confirmations AND 7.0 seconds of sustained readings before committing a switch to a cross street. Verified by successful compilation. |
| **GEO-02** | Grace period for briefly disappearing street names (15s decay timer before clearing) (Item 7). | `LocationManager.kt` | **Completed** | Introduced `lastStreetSeenTimestamp` 15-second decay grace period; temporary reverse-geocode blackouts retain the last valid street name. |
| **GEO-03** | Road & Street normalization: canonical DK52/DW946 abbreviations, strip house numbers from major roads, polish street abbreviations (Item 10). | `RoadNameNormalizer.kt`, `RoadNameNormalizerTest.kt` | **Completed** | Canonical normalization for DK*, DW*, A*, S*, E* with house numbers stripped on highways and preserved on residential streets. Verified with 7 passing JUnit unit tests (`testDebugUnitTest`). |
| **GEO-04** | Municipality (`gmina`) field added to location data structure & smart deduplication (Item 12). | `LocationManager.kt` (`formatHierarchy`) | **Completed** | Reverse geocoding extracts `municipality`/`commune`/`gmina` and formats `gm. X` with clean deduplication against city and powiat (prevents `gm. Maków Podhalański` when city is Maków Podhalański). |
| **GEO-05** | Town boundaries resilience, offline polygon caching, and eliminate 429 rate limits (Item 18, Screenshots 084909, 084918, 091025). | `BoundaryHelper.kt` | **Completed** | 2-tier caching: in-memory `ConcurrentHashMap` + persistent on-disk JSON storage in `cacheDir/boundaries`. Nominatim 1.5s rate-limit mutex and 30s exponential cooldown on HTTP 429. |
| **GEO-06** | Intentional visit filter (penetrate > 150m OR stay > 45s) to eliminate border-skimming flip-flops (Item 9). | `TripManager.kt` | **Completed** | `PendingPlaceCandidate` tracks candidate locality entry; qualifies as intentional visit only if displacement >= 150m or duration >= 45s. Transient boundary skimming along roads like DK28 is filtered out. |
| **GEO-07** | Extend town & street name cache duration for stability (Item 8). | `LocationManager.kt` | **Completed** | 30-minute spatial grid LRU cache (~15m cell resolution, up to 300 entries) and 30-minute LRU cache for Nominatim responses with 1.0s query throttling. |

---

## 4. Phase 3: Map Controls, Overlays & Stationary Bearing

| Item ID | User Request / Description | Target Components / Files | Status | Verification & Evidence |
| :--- | :--- | :--- | :---: | :--- |
| **MAP-R01** | Controllable map label/font size via map settings selector (`Normal 100%`, `Large 135%`, `Extra Large 170%`) (Item 1). | `OsmMapView.kt` | **Completed** | Implemented `MapFontScale` with `mapView.tilesScaleFactor` applied dynamically and persisted in `SharedPreferences`. Verified in `assembleDebug`. |
| **MAP-R02** | Hiking / Tourist trail overlay (Waymarked Trails) with color-coded tracks, peaks, passes, and satellite base layer switch (Item 3). | `OsmMapView.kt` | **Completed** | Added `MapBaseLayer` (Standard OSM, OpenTopoMap, Esri World Imagery Satellite) and dynamic `TilesOverlay` with Waymarked Trails Hiking transparent overlay. Settings accessible via dialog. |
| **MAP-R03** | Stationary bearing freeze: maintain driving orientation arrow when stopped instead of rotating randomly (Item 4). | `LocationManager.kt`, `OsmMapView.kt`, `LiveSharingManager.kt` | **Completed** | `LocationManager.kt` captures `lastValidBearing` when moving $\ge 1.2\text{ m/s}$ and preserves it when stationary; `OsmMapView.kt` maintains `lastFrozenBearing` for cursor rotation and `COURSE_UP` orientation. Live sharing payload transmits bearing. |
| **MAP-R04** | Compass on map in live viewer (Item 14). | `server/public/index.html` | **Completed** | Implemented interactive compass rose widget on live viewer web page with 3D needle dynamically rotating based on `data.current.bearing` and cardinal degree readouts. |
| **MAP-R05** | Restore "Refresh Map / Recenter" button (Item 17). | `OsmMapView.kt` | **Completed** | Added dedicated Refresh Map floating action button (purges tile memory cache, recenters to GPS, triggers redraw) and Map Layers/Settings button. |

---

## 5. Phase 4: Live Sharing Power-Up & Web Viewer

| Item ID | User Request / Description | Target Components / Files | Status | Verification & Evidence |
| :--- | :--- | :--- | :---: | :--- |
| **LIV-R01** | Persistent foreground notification with live stats (duration, activity, points) and quick actions (Pause, Sync Now, Stop) (Item 22). | `LiveTrackingService.kt` | **Completed** | Foreground notification updated with real-time stats (`Maków Podhalański • 14.2 km (54 km/h) • [LIVE] • ⏱️ 1h 12m elapsed`) and 3 action buttons: `⏸ Pause` / `▶ Resume`, `🔄 Sync Now`, and `⏹ Stop`. |
| **LIV-R02** | Elapsed session time display for infinite/continuous sharing instead of countdown timer (Item 22). | `LiveSharingManager.kt`, `MainActivity.kt`, `index.html` | **Completed** | Both mobile UI and live web viewer calculate `Date.now() - createdAt` when `expiresAt == 0`, formatting as `⏱️ Xh Ym elapsed` instead of counting down or showing "Expired". |
| **LIV-R03** | On-the-fly live renaming without stopping session (Item 22). | `LiveSharingManager.kt`, `MainActivity.kt`, `server/server.js` | **Completed** | Added inline editable title in `LiveSharingManagementSheet` calling `renameSession()`, communicating with new backend endpoint `POST /api/sessions/:id/rename`. Web viewers update immediately. |
| **LIV-R04** | On-the-fly live update frequency switching (1m, 2m, 5m, 10m) (Item 15, 16). | `LiveSharingManager.kt`, `MainActivity.kt`, `server/server.js` | **Completed** | Added live frequency selector buttons (`1m`, `2m`, `5m`, `10m`) in management sheet, updating mobile sync threshold and server session configuration via `POST /api/sessions/:id/interval`. |
| **LIV-R05** | Selective pause/stop for Personal link vs Random link independently (Item 22). | `LiveSharingManager.kt`, `MainActivity.kt`, `server/server.js` | **Completed** | Added independent flags `isPersonalPaused` and `isRandomPaused`. Management sheet provides separate buttons: "Pause/Resume Static" and "Pause/Resume Trip", allowing host to selectively pause the personal link while keeping single-trip links active. |
| **LIV-R06** | Visitors live sharing page: Open in Google Maps, responsive mobile layout, and visit counter analytics (Item 21, Screenshot 132117). | `server/public/index.html`, `server/server.js` | **Completed** | Live web page now features "🗺️ Open in Google Maps" deep-link button, total visitor count chip (`👁️ X views` tracked server-side), and responsive layout with floating compass rose widget. |

---

## 6. Features Inventory & Future Backlog

| Backlog ID | Feature Description | Category | Target Milestone |
| :--- | :--- | :---: | :---: |
| **BKL-01** | Root URL landing page (`/` or `/live/` without ID) with browser cookie/localStorage remembered past sessions (Item 5). | Web Platform | Future Sprint |
| **BKL-02** | Server data retention policy and TTL cleanup job for expired session breadcrumbs (Item 6). | Server Architecture | Future Sprint |
| **BKL-03** | Visitor on-demand location refresh request from web page to mobile app (Item 16). | Live Protocol | Future Sprint |
| **BKL-04** | Historical shared routes catalog portal (Item 21c). | Web Platform | Future Sprint |
