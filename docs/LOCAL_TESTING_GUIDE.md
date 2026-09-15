# Local End-to-End Testing Guide (USB & Docker)

This guide walks you through setting up and running the complete **WhereAmI** ecosystem locally on your development machine, connecting your physical Android phone via USB, and validating live location tracking end-to-end with the self-hosted Docker container.

---

## Architecture Overview

```
┌──────────────────────────────────────┐          USB Cable          ┌─────────────────────────────────────────┐
│            Android Phone             │ ─────────────────────────── │              Host PC / Mac              │
│                                      │                             │                                         │
│ • WhereAmI App (with USB detection)  │                             │ • ADB reverse: tcp:3003 -> tcp:3003     │
│ • Target: http://127.0.0.1:3003      │ ──[ ADB Port Forwarding ]── │ • Docker Container (whereami-live:3003) │
│ • Smooth breadcrumb telemetry stream │                             │ • Browser Viewer: localhost:3003/live/… │
└──────────────────────────────────────┘                             └─────────────────────────────────────────┘
```

When connected over USB, running `adb reverse tcp:3003 tcp:3003` maps any request the Android device makes to `http://127.0.0.1:3003` directly across the USB link into your computer's port `3003`. This bypasses local Wi-Fi router isolation, corporate firewalls, and dynamic IP configurations.

---

## Prerequisites

1. **Host Machine**: Docker Desktop (or Docker Engine) with Docker Compose installed.
2. **Android SDK Platform Tools**: `adb` available on your `PATH`.
3. **Android Device**:
   - Developer options enabled.
   - USB Debugging enabled.
   - Connected via USB cable.

---

## Step 1: Start the Local Live Server in Docker

The `server/` directory includes a multi-architecture Dockerfile and `docker-compose.yml` matching the exact environment used on Synology NAS.

1. Open your terminal and navigate to the `server/` directory:
   ```bash
   cd server
   ```

2. Build and start the container in detached mode:
   ```bash
   docker compose up -d --build
   ```

3. Verify the container is running and healthy:
   ```bash
   docker compose ps
   ```
   You can also test the health endpoint in PowerShell:
   ```powershell
   Invoke-RestMethod -Uri "http://localhost:3003/health"
   ```
   *Expected response:* `@{status=ok; uptime=...; sessions=...}`

---

## Step 2: Establish the ADB Reverse Tunnel (USB Port Forwarding)

1. Verify your phone is recognized by ADB:
   ```bash
   adb devices
   ```
   *Expected output:* A device ID followed by `device` (if `unauthorized`, accept the prompt on your phone).

2. Reverse port 3003:
   ```bash
   adb reverse tcp:3003 tcp:3003
   ```
   *Confirmation:* No output or `3003` printed. Any `http://127.0.0.1:3003` traffic initiated on your phone is now forwarded directly to your PC Docker container!

3. To verify active reverse mappings:
   ```bash
   adb reverse --list
   ```

---

## Step 3: Test in the Android App

1. Launch **WhereAmI** on your phone.
2. Ensure the phone is plugged in via USB:
   - The app automatically detects the USB connection via hardware state and battery manager.
3. Tap the **📡 Live Sharing** button on the bottom control bar to open the Live Share dialog:
   - In the **Share Target / Provider** dropdown, observe that **`💻 Local Test Server (Port 3003)`** is active and selectable (it displays a USB required warning if unplugged).
   - Select **`💻 Local Test Server (Port 3003)`**.
   - Choose a sync interval (e.g., `1m` or `2m` for rapid testing).
   - Note the pre-generated Trip Session ID or Personal Static Live ID.
4. Tap **Start Live Sharing Session**.

---

## Step 4: Verify in Your Desktop Browser

1. Open your browser on the host PC:
   - If using a Trip Link:
     ```
     http://localhost:3003/live/<session-id>
     ```
   - If using your Personal Static ID:
     ```
     http://localhost:3003/live/<static-id>
     ```
   *(You can also click **Copy** inside the app's Active Session Card to get the exact link).*

2. **Verify Live Updates**:
   - Watch the breadcrumb line draw smoothly as you move or simulate location.
   - Check the speed, altitude, and battery metrics at the top of the viewer.
   - In the app, test toggling **`[ 🗺️ Full Trail | 📍 Position Only ]`**: notice the browser dynamically shows or hides the full trail without disconnecting.
   - In the app, tap **`⏸️ Pause Live Sharing`**: notice the web viewer immediately transitions to amber `PAUSED` banner.
   - Tap **`▶️ Resume Live Sharing`**: notice the viewer resumes live tracking with the pulsing dot.

---

## Step 5: Clean Up

When you are done testing:

1. Stop live sharing in the app.
2. Stop the local Docker container:
   ```bash
   docker compose down
   ```
3. Remove the ADB port forwarding:
   ```bash
   adb reverse --remove tcp:3003
   ```
