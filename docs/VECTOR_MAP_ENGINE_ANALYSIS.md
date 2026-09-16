# Vector Map Engine Feasibility Analysis: MapLibre Native for Android

## 1. Executive Summary & Problem Statement

### The Problem with Raster Tile Maps (Osmdroid)
WhereAmI currently uses **Osmdroid**, an open-source raster tile renderer.
- **Root Cause of Text Blur**: Raster tiles are 256x256 or 512x512 pre-rendered pixel bitmaps. Scaling them up via `tilesScaleFactor` (e.g. 200%–340%) simply magnifies existing pixel grids using linear interpolation, resulting in blurry, fuzzy text and smudged symbols.
- **Upside-Down Labels**: When the map rotates (e.g. Course-Up / AUTO mode), the entire canvas rotates, causing street names and city labels to appear upside down or vertically inverted.
- **Fixed Styles**: You cannot dynamically adjust street label font size independently from building outlines or road widths.

### The Vector Solution: MapLibre Native Android
**MapLibre Native** renders vector tiles (`.mvt` / Mapbox Vector Tile format) directly on the device GPU via OpenGL ES / Vulkan:
1. **Crystal-Clear Arbitrary Font Scaling**: Text is rendered as vectorized glyphs directly from signed distance fields (SDF) or vector fonts at any size (16sp, 20sp, 28sp) without a single blurry pixel.
2. **Auto-Upright Labels**: Regardless of how fast or far the map rotates in Course-Up mode, all text labels automatically orient upright toward the user's perspective.
3. **Dynamic Styling**: Road widths, trail marker colors, contour lines, and place names can be styled in real-time without fetching new tiles.

---

## 2. Open-Source Licensing & Commercial Safety

- **License**: **Apache 2.0** (100% Free and Open Source Software).
- **Governance**: Governed by the MapLibre Open Source Collective under the Linux Foundation.
- **Cost**: **$0.00**. Completely free of proprietary fees, API keys, or tracking tokens.
- **Android Support**: First-class Android library (`org.maplibre.gl:android-sdk:11.5.0+`) with official Jetpack Compose bindings (`org.maplibre.gl:android-compose`).

---

## 2.1 Commercial Monetization & Apache 2.0 Licensing Rules

> **Bottom-Line Answer**: **YES, you can 100% legally sell your app for money, charge subscriptions, or monetize it on Google Play without paying any royalties or fees to the MapLibre project.**

The **Apache License, Version 2.0** is one of the most commercially permissive licenses in software engineering. Unlike "viral" copyleft licenses (like GNU GPL / AGPL), Apache 2.0 was designed specifically for commercial and proprietary software development.

### 1. What Rights Does Apache 2.0 Grant You?
- **Commercial Use**: You can sell the app, charge a purchase price, charge recurring subscriptions, or use in-app purchases.
- **Closed Source / Proprietary Distribution**: You **DO NOT** have to open-source your own application, your proprietary algorithms, your UI, or your backend server code.
- **Modification**: You can modify the library's code if needed.
- **Patent Grant**: Contributors grant an express patent license for their contributions.

### 2. What Conditions MUST You Meet to Legally Charge for Your App?
Apache 2.0 requirements (Section 4 of the license) are straightforward and standard for all commercial Android apps:

1. **Include a Copy of the Apache 2.0 License**:
   - Your app must include the text of the Apache 2.0 license.
   - *How to satisfy this in Android*: Add a simple "About / Legal / Open Source Licenses" screen or dialog in Settings that displays the license text.
2. **Retain Copyright & Attribution Notices**:
   - You must include the original copyright notice from the library (e.g. `Copyright (c) MapLibre contributors`).
3. **Retain the `NOTICE` File (if present)**:
   - If the MapLibre SDK repository contains a `NOTICE` text file, you must include its contents in your legal notices section.
4. **State Modifications (Only If You Modify MapLibre's Own Source Code)**:
   - If you simply add `implementation "org.maplibre.gl:android-sdk:..."` as a Gradle dependency, you did not modify their code, so this condition does not apply.
   - If you fork and alter MapLibre's internal source files, those specific files must contain a prominent notice stating that you changed them.
5. **No Trademark Misrepresentation**:
   - You cannot name your app "The Official MapLibre Navigator" or use their logos in a way that implies endorsement by MapLibre. (You can state: *"Powered by MapLibre Native"*).
6. **Warranty Disclaimer**:
   - You must accept that the open-source library is provided "AS IS", without warranty from its original creators.

---

### 3. The Crucial Distinction: Map Engine vs. Map Data vs. Tile Hosting

When monetizing a mapping app, three distinct layers exist:

| Layer | Component | License / Cost | Commercial Rules |
| :--- | :--- | :--- | :--- |
| **1. The Rendering Engine** | **MapLibre Native Android SDK** | **Apache 2.0** | 100% free, commercial use allowed, closed-source app allowed. Just include license/copyright in an About screen. |
| **2. The Map Data** | **OpenStreetMap (OSM)** | **ODbL (Open Database License)** | You **CAN** sell an app displaying OSM data. You **MUST** display attribution on the map or in the app: `© OpenStreetMap contributors`. |
| **3. The Vector Tile Server** | **Tile Hosting / Pipeline** | Variable | • **Self-Hosted / Offline (`.pmtiles`, `.mbtiles`, or your Synology NAS)**: **100% free forever, $0 recurring fees**.<br>• **Commercial 3rd-Party APIs (MapTiler, Stadia Maps)**: If you use their cloud tile servers, they charge based on monthly tile requests once your app scales. |

### Summary Recommendation
If you switch to the MapLibre vector engine and distribute WhereAmI as a paid app:
1. Add an **"Open Source Licenses"** item in Settings showing the Apache 2.0 license and MapLibre attribution.
2. Keep the standard **`© OpenStreetMap contributors`** attribution text.
3. Bundle offline vector tiles or stream them from your own Synology NAS to avoid recurring third-party tile API bills.

---

## 3. Offline Vector Data Options

| Solution | Storage Size (Poland) | Pros | Cons |
|---|---|---|---|
| **PMTiles Extract** (Protomaps) | ~350 MB – 550 MB | Single self-contained archive, zero server needed, instant offline random-access tile reading. | Requires reading via local HTTP server or native PMTiles archive handler. |
| **MBTiles SQLite Container** | ~400 MB – 600 MB | Standard SQLite database format, natively read by MapLibre Native without any local server. | Slightly larger than PMTiles. |
| **Online Vector Tile CDN** (OpenMapTiles / Stadia / MapTiler) | 0 MB local storage | Extremely fast, dynamic updates, global coverage. | Requires free tier API key or hosting own tile server. |

---

## 4. Recommended Migration Roadmap

### Phase 1: Dual Engine Architecture (Non-Breaking)
1. Keep Osmdroid as the default stable raster engine.
2. Introduce a `MapEngine` enum in Settings (`OSMDROID_RASTER` vs `MAPLIBRE_VECTOR`).
3. Add MapLibre dependency (`org.maplibre.gl:android-sdk`) without removing Osmdroid.

### Phase 2: Jetpack Compose Vector Canvas
1. Implement `MapLibreComposeView` wrapping `MapView`.
2. Connect existing GPS tracking, active trip polylines, visited places pins, and boundary overlays to MapLibre's GeoJSON data sources.
3. Add font scale slider that directly controls the style JSON text property:
   ```json
   "text-size": ["interpolate", ["linear"], ["zoom"], 10, 14, 16, 22]
   ```

### Phase 3: Bundled Offline Extract
1. Offer in-app download of `poland.mbtiles` (~450MB) stored in `context.getExternalFilesDir()`.
2. Seamlessly transition from online vector streaming to local MBTiles when cellular reception drops.
