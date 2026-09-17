# Field Testing Analysis & Technical Specification (2026-09-17)

**Date**: 2026-09-17  
**Author**: Antigravity Agent & Janusz Hatala  
**Target Platform**: Android (WhereAmI app), Android Auto (`AutoMediaService`), Companion Web Sync Viewer (`server/`)  
**Data Extraction Status**: **Completed** (Full binary database, SharedPreferences, and device dump extracted from Pixel 10 Pro via ADB into `device_dump_20260917/`).

---

## 1. Executive Summary & Root-Cause Diagnosis

During the field test on 2026-09-17, comprehensive GPS tracking, Android Auto media session broadcast, live sharing, and UI interactions were evaluated. Deep investigation of the extracted device database (`where_i_am_trips.db`), disk preferences (`where_i_am_prefs.xml`), and the codebase revealed the exact mechanisms behind every reported anomaly:

### Critical Root-Cause Findings

1. **The "Bielsko-Biała in gm. Porąbka" Anomaly (Bug 2 & Screenshot 071628)**:
   - **Device Data Proof**: In `device_dump_20260917/where_i_am_prefs.xml` line 29:
     ```xml
     <string name="loc_gmina_bielsko-biała">gmina Porąbka</string>
     ```
     And in `where_i_am_trips.db` (Trip ID 34 from 2026-09-17 06:52-07:52):
     ```json
     {
       "name": "Bielsko-Biała",
       "sub": "gm. Porąbka • woj. śląskie",
       "time": 1789622139446,
       "lat": 49.8272941,
       "lng": 19.1195064,
       "dist": 15279.19
     }
     ```
   - **Root Cause**: In `LocationManager.kt`, administrative hierarchy resolution used `distToLastGood < 1500f` to backfill `candidateGmina` when the geocoder/Nominatim returned `null`. Because Bielsko-Biała is an independent city with county rights (`miasto na prawach powiatu`), neither the Android Geocoder nor Nominatim returns a separate rural gmina.
   - Because the previous trip segments passed through Czaniec, Kobiernice, and Bujaków (all in Gmina Porąbka), `lastGood.gmina` was `"gmina Porąbka"`. When crossing into Bielsko-Biała $< 1500\text{m}$ from the prior fix, it inherited `"gmina Porąbka"`.
   - Worse, `loc_gmina_bielsko-biała` was permanently written to SharedPreferences, causing every subsequent launch and fix in Bielsko-Biała to display `gm. Porąbka`!

2. **UI & Android Auto Blinking / Street Oscillation (Bug 1)**:
   - **Root Cause**: In `LocationManager.kt`, `getLocationUpdates()` emits twice per fix:
     1. **Fast-path**: Emits `committedPlace` synchronously to update the speedometer immediately.
     2. **Async-path**: Launches `ioScope.launch` to resolve geocoding, calls `applyStreetHysteresis`, and emits `data`.
   - However, `committedPlace` was NOT updated with `applyStreetHysteresis`! Consequently, the fast-path emitted the *raw/previous* street, and ~300ms later the async path emitted the *stabilized* street. On the next GPS tick (1 second later), the fast-path reverted to the old street again, creating high-frequency blinking.
   - Concurrency Race: Both `MainActivity` and `AutoMediaService` called `getLocationUpdates()` independently. Each call created its own `LocationCallback` and shared mutable variables (`candidateStreetCount`, `committedStreetPl`), causing race conditions and freezing Android Auto metadata.

3. **Speedometer Lagging and Dropping to 0.0 km/h While Driving (Bug 1 & Screenshots 070652/070655)**:
   - **Root Cause**: `StationaryDetector.kt` uses an accelerometer variance threshold (`< 0.045`). In a rigid vehicle mount cruising smoothly on freshly paved roads (like DK52 or Krakowska street), acceleration variance drops below 0.045.
   - In `LocationManager.kt` (`hybridSpeedUpdate`), if `isPhysicallyStationary` was true, it **unconditionally forced speed to 0.0 km/h**, completely disregarding positive GPS Doppler speed ($> 20\text{ km/h}$).

4. **Trip Route Truncation & Pause Clutter (Bug 6 & Screenshot 081937)**:
   - **Device Data Proof**: In `where_i_am_trips.db` (Trip ID 31 from 2026-09-16 06:33-10:33):
     - **Distance**: 82.27 km, **Max Speed**: 74.0 km/h, **Avg Speed**: 38.6 km/h.
     - **Places Visited**: 26 places!
       `Czaniec -> Kobiernice -> Czaniec -> Roczyny -> Andrychów -> Roczyny -> Czaniec -> Bulowice -> Andrychów -> Inwałd -> Chocznia -> Wadowice -> Jaroszowice -> Wadowice -> Gorzeń Górny -> Świnna Poręba -> Jaroszowice -> Gorzeń Górny -> Wadowice -> Chocznia -> Inwałd -> Andrychów -> Wieprz -> Andrychów -> Bulowice -> Czaniec`
     - **Pauses**: 18 separate pauses recorded!
   - In the UI (`MainActivity.kt`), the route string had `maxLines = 2`, cutting off after `Bulowice -> ` and hiding 70% of the journey. All 18 pauses were expanded by default, taking up massive vertical space.

5. **Saved Places Editing Missing (Bug 6 & Screenshot 081818)**:
   - **Device Data Proof**: The SQLite `saved_places` table contains 3 records:
     - ID 1: "Dom" (`HOME`), Czaniec, Zielona 92
     - ID 2: "V" (`CUSTOM`), Bielsko-Biała, Józefa Lompy 10
     - ID 3: "Timebook" (`WORK`), Bielsko-Biała, Dworkowa 2
   - `TripDatabaseHelper.updateSavedPlace()` is already implemented, but `MainActivity.kt` lacks an Edit dialog or button, and `PlaceCategory.SCHOOL` was omitted from the UI category list.

6. **Pin Dropped in Forest Displaying Street & House Number (Bug 8 & Screenshot 093705)**:
   - **Root Cause**: In `SearchHelper.reverseGeocode()`, Android Geocoder's `Address` envelope was formatted as `$street $houseNum` regardless of distance to the nearest road. When dropped on mountain/forest terrain, it grabbed the nearest postal address (e.g. `Świerkowa 6, Bystra`) hundreds of meters away.

---

## 2. Itemized Feedback Breakdown & Action Specifications

### Item 0: Telemetry & Phone Data Extraction
- **Status**: **Completed**. Extracted:
  - `where_i_am_trips.db` (815 KB, 25 trips, 3 saved places)
  - `where_i_am_prefs.xml` (3.5 KB)
  - `where_i_am_live_share_prefs.xml` (1.2 KB)
  - `where_i_am_trip_prefs.xml` (AUTO mode, CAR profile, 5 min auto-stop)
  - `where_i_am_ui_prefs.xml` (COURSE_UP, show borders true, keep screen on true)
  - `whereiam_map_prefs.xml` (font scale MAXIMUM, standard layer)
  - `logcat.txt` (2.3 MB)

### Item 1: Kinematic Speed, Android Auto Stability & Street Recognition
- **Speedometer**:
  - Never allow `StationaryDetector` (accelerometer clamp) to zero out speed if GPS reports valid velocity ($> 1.5\text{ m/s}$) or physical displacement $> 3\text{m}$.
  - Accelerometer variance is strictly an indoor/nightstand jitter dampener ($< 0.35\text{ m/s}$).
  - Tighten Kalman window from 3 ticks to 1-2 ticks for immediate speedometer response.
- **Single Source of Truth (SSOT)**:
  - Refactor `LocationManager` to expose a single shared `StateFlow<LocationData>`.
  - Both `MainActivity` and `AutoMediaService` observe this shared stream, eliminating duplicate location callbacks, mutex contention, and frozen Android Auto screens.
- **Street Recognition & Blinking**:
  - Fix double-emission flicker: update `committedPlace` only after street hysteresis is applied.
  - Prioritize canonical road/street name without house number when geocoding is ambiguous.
  - Require stricter corridor inertia when driving on main roads (DK, DW, primary corridors) before switching to side residential streets.

### Item 2: Bielsko-Biała Municipality Hierarchy Bug
- **Fix**:
  - Remove unsafe `distToLastGood < 1500f` cross-city gmina inheritance.
  - Detect cities with county rights (`miasto na prawach powiatu` / city == county) and suppress `gm.` entirely.
  - Add cache sanitization on app start to purge invalid `loc_gmina_*` keys (e.g. `loc_gmina_bielsko-biała`).

### Item 3: Main Location Panel Header Rearrangement
- **Target Layout**:
  - **Left**: Google Maps icon/button, followed by Live Sharing button/status pill.
  - **Right**: Bookmarked place icon/label (clickable to jump directly to Places tab in Trips & Places), followed by Expand/Collapse button.

### Item 4: Rename "Places & Trips" to "Trips & Places"
- Update all occurrences across UI, tabs, dialogs, headers, and descriptions.

### Item 5: Live Visitors Web Page (`server/public/index.html`)
- **Last Seen Label**: Change bare time (e.g. `13s ago`) to explicit label: `Last seen: 13s ago`.
- **Fit Trip Button**: Add a floating map button to fit full polyline bounds (`map.fitBounds()`).
- **Visual Center Recenter**: Center user marker in visible aperture below top card.
- **Top Card Header Wrap**: Status badge in top-right, elapsed time below, graceful wrapping for long titles.

### Item 6: Trips & Places Panel Enhancements
- **Places**: Add Edit button and dialog to modify place name, category, and radius.
- **Trips Pauses**:
  - Collapse Rest Pauses by default in trip cards.
  - Add per-trip collapse toggle.
  - Add global "Expand All / Collapse All" button next to "Select All" when 0 trips selected.
- **Route Text Display**: Remove `maxLines = 2` clamp; allow complete 26-locality route to display.
- **Database-Level Filtering & Grouping**:
  - Dynamic SQLite queries across all stored trips (not only loaded in-memory items).
  - Search trips by route locality name.
  - Group By toggle: by Time (Today, This Week, This Month, Earlier / Year) and Activity Type.

### Item 7: Live Location Sharing Panel Reorganization
- **Top Action**: Move "Start Live Sharing Session" directly beneath title card.
- **Active State Row**: Compact button bar: `[Stop]` (red), `[Pause]` (amber), `[Sync Now]` (blue).
- **Permanent Streaming Controls**: Remove expandable disclosure arrow; render Privacy (`Full Trail` / `Position Only`) and Update Frequency inline directly below buttons.
- **Footer Section**: Provider, Duration, and Share Links at the bottom.

### Item 8: Map Pin & Forest Address Resolution
- **Forest / Off-Road Coordinates**:
  - If dropped pin is $> 150\text{m}$ away from resolved address or on forest/natural terrain, display coordinates and administrative hierarchy (locality/municipality/county/voivodeship/country) instead of snapping to a distant house number.
- **Button Cleanup**: Remove redundant "Exit Pin" button (top-right X handles dismissal).
- **Pin Locality Boundaries**:
  - Add button in Pin card to show boundary of pinned locality.
  - Render pinned boundary in distinct styling (cyan dashed border with subtle tint).
  - Keep both boundaries visible simultaneously (current GPS location and pinned locality) so the user can easily compare both areas.
  - Reset pin boundary on new pin drop or pin card dismissal.

### Item 9: Viewport-Aware Map Recenter / Center Action
- Compute optical center offset based on top card height (both Spatious ~180dp and Condensed ~75dp) and bottom toolbar (~70dp) so user position is centered in visible aperture.

### Item 10: School Place Category
- Add `PlaceCategory.SCHOOL` ("School", "🏫") to category selection in Save/Edit Place dialogs.

---

## 3. Verified Field Evidence Summary

| Artifact / Key | Stored Value | Significance / Impact |
|---|---|---|
| `where_i_am_prefs.xml` (`loc_gmina_bielsko-biała`) | `"gmina Porąbka"` | Confirmed cause of permanent Bielsko-Biała gmina corruption. |
| `where_i_am_trips.db` (Trip ID 34) | Places: `Czaniec -> Kobiernice -> Bujaków -> Kozy -> Bielsko-Biała` | Verified today's morning test drive; Bielsko-Biała recorded with `gm. Porąbka`. |
| `where_i_am_trips.db` (Trip ID 31) | 82.27 km, 18 pauses, 26 places | Proved pause clutter and 2-line route truncation in actual field usage. |
| `saved_places` table | 3 places (Dom, V, Timebook) | Confirmed SQLite integrity; missing Edit UI prevents modification. |
| `where_i_am_ui_prefs.xml` | `pref_map_orientation_mode: COURSE_UP` | Proved Course-Up mode is active during driving. |
