#!/bin/bash
set -eo pipefail

PACKAGE_NAME="janush.tech.whereami"
TARGET_DIR="${1:-.device_telemetry/latest}"

# Ensure output directory exists
mkdir -p "$TARGET_DIR"

echo "=== WhereAmI Device Telemetry Puller ==="

# Check connected devices
DEVICES=$(adb devices | grep -w "device" | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -v '^$' | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ Error: No Android device found via ADB."
    echo "👉 Please connect your phone via USB (with USB Debugging enabled) or adb connect <IP>:5555"
    exit 1
fi

DEVICE_ID=$(echo "$DEVICES" | head -n 1)
echo "📱 Found device: $DEVICE_ID (Total devices: $DEVICE_COUNT)"

# Verify run-as permissions
if ! adb -s "$DEVICE_ID" shell "run-as $PACKAGE_NAME id" >/dev/null 2>&1; then
    echo "❌ Error: Unable to access app sandbox via 'run-as $PACKAGE_NAME'."
    echo "Ensure the debug build of $PACKAGE_NAME is installed on the device."
    exit 1
fi

echo "📦 Access granted to sandbox: $PACKAGE_NAME"

# 1. Pull SQLite Databases
echo "💾 Pulling SQLite databases..."
adb -s "$DEVICE_ID" exec-out run-as "$PACKAGE_NAME" cat databases/where_am_i_trips.db > "$TARGET_DIR/where_am_i_trips.db" 2>/dev/null || echo "⚠️ Could not pull where_am_i_trips.db"
adb -s "$DEVICE_ID" exec-out run-as "$PACKAGE_NAME" cat databases/where_am_i_spatial_cache.db > "$TARGET_DIR/where_am_i_spatial_cache.db" 2>/dev/null || echo "⚠️ Could not pull where_am_i_spatial_cache.db"

# 2. Pull SharedPreferences
echo "⚙️ Pulling SharedPreferences..."
mkdir -p "$TARGET_DIR/shared_prefs"
PREFS_FILES=$(adb -s "$DEVICE_ID" shell "run-as $PACKAGE_NAME ls shared_prefs/" 2>/dev/null | tr -d '\r')
for PREF in $PREFS_FILES; do
    if [ -n "$PREF" ]; then
        adb -s "$DEVICE_ID" exec-out run-as "$PACKAGE_NAME" cat "shared_prefs/$PREF" > "$TARGET_DIR/shared_prefs/$PREF" 2>/dev/null || true
    fi
done

# 3. Pull System & App Logcat
echo "📋 Pulling logcat logs..."
adb -s "$DEVICE_ID" logcat -d > "$TARGET_DIR/logcat_full.txt" 2>/dev/null || echo "⚠️ Could not dump full logcat"
adb -s "$DEVICE_ID" logcat -d | grep -E "WHEREAMI|GEOCODE|STREET|TRIP|LIVE|LOCATION|OSM|TRACKING|AndroidRuntime" > "$TARGET_DIR/logcat_app.txt" 2>/dev/null || true

echo "✅ Telemetry successfully downloaded to: $TARGET_DIR"
ls -lh "$TARGET_DIR"
if [ -f "$TARGET_DIR/where_am_i_trips.db" ]; then
    echo ""
    echo "📊 Quick Trip Database Summary:"
    sqlite3 "$TARGET_DIR/where_am_i_trips.db" "SELECT count(*) AS total_trips FROM trips; SELECT id, title, start_time, distance_meters, avg_speed_kmh, profile FROM trips ORDER BY id DESC LIMIT 3;" 2>/dev/null || true
fi
