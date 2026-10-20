package janush.tech.whereami

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import janush.tech.whereami.tracking.TrackPointRow

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.also { StorageMigrationHelper.migrateDatabaseIfNeeded(it) },
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = StorageMigrationHelper.CANONICAL_DB_NAME
        private const val DATABASE_VERSION = 11

        private const val TABLE_TRIPS = "trips"
        private const val COL_ID = "id"
        private const val COL_TITLE = "title"
        private const val COL_START_TIME = "start_time"
        private const val COL_END_TIME = "end_time"
        private const val COL_DISTANCE = "distance_meters"
        private const val COL_MAX_SPEED = "max_speed"
        private const val COL_AVG_SPEED = "avg_speed"
        private const val COL_IS_AUTO = "is_auto"
        private const val COL_ACTIVITY_PROFILE = "activity_profile"
        private const val COL_POINTS_JSON = "points_json"
        private const val COL_PLACES_JSON = "places_json"
        private const val COL_PAUSES_JSON = "pauses_json"
        /** 0 = geometry still lives in points_json (not yet backfilled), 1 = geometry lives in track_points. */
        private const val COL_POINTS_STORE = "points_store"

        // Track points table (v9): one row per recorded sample
        private const val TABLE_TRACK_POINTS = "track_points"
        private const val TABLE_TRIP_MATCHES = "trip_matches"

        /** trip_matches.state values */
        const val MATCH_PENDING = 0
        const val MATCH_DONE = 1
        const val MATCH_REJECTED = 2
        const val MATCH_MAX_ATTEMPTS = 5
        private const val COL_TP_TRIP = "trip_id"
        private const val COL_TP_SEQ = "seq"
        private const val COL_TP_T = "t_utc"
        private const val COL_TP_LAT = "lat"
        private const val COL_TP_LNG = "lng"
        private const val COL_TP_ALT = "alt"
        private const val COL_TP_ALT_SRC = "alt_src"
        private const val COL_TP_SPEED = "speed"
        private const val COL_TP_HACC = "h_acc"
        private const val COL_TP_BEARING = "bearing"
        private const val COL_TP_FLAGS = "flags"

        // Saved Places Table
        private const val TABLE_SAVED_PLACES = "saved_places"
        private const val COL_SP_ID = "id"
        private const val COL_SP_NAME = "name"
        private const val COL_SP_CATEGORY = "category"
        private const val COL_SP_LAT = "latitude"
        private const val COL_SP_LNG = "longitude"
        private const val COL_SP_RADIUS = "radius_meters"
        private const val COL_SP_LOCALITY = "locality"
        private const val COL_SP_STREET = "street"
        private const val COL_SP_CREATED_AT = "created_at"
        private const val COL_SP_COLOR = "color"
        private const val COL_SP_ALTITUDE = "altitude"
        private const val COL_SP_ALTITUDE_SOURCE = "altitude_source"

        // Local SQLite Elevation Cache
        private const val TABLE_ELEVATION_CACHE = "elevation_cache"
        private const val COL_EC_GRID_KEY = "grid_key"
        private const val COL_EC_ELEVATION = "elevation"
        private const val COL_EC_UPDATED_AT = "updated_at"

        fun cleanPartSuffix(title: String): String {
            return title.replace(Regex("""\s*[-–(]\s*Part\s*\d+\)?.*""", RegexOption.IGNORE_CASE), "").trim()
        }

        internal fun pausesToJson(pauses: List<TripPause>): String {
            val array = JSONArray()
            for (p in pauses) {
                val obj = JSONObject().apply {
                    put("start", p.startTime)
                    if (p.endTime != null) put("end", p.endTime)
                    put("lat", p.latitude)
                    put("lng", p.longitude)
                    put("dur", p.durationMs)
                    put("idx", p.pointIndex)
                    if (p.distanceMeters > 0.0) put("dist", p.distanceMeters)
                }
                array.put(obj)
            }
            return array.toString()
        }

        internal fun jsonToPauses(jsonStr: String): List<TripPause> {
            val list = mutableListOf<TripPause>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val endTime = if (obj.has("end") && !obj.isNull("end")) obj.getLong("end") else null
                    list.add(
                        TripPause(
                            startTime = obj.getLong("start"),
                            endTime = endTime,
                            latitude = obj.getDouble("lat"),
                            longitude = obj.getDouble("lng"),
                            durationMs = obj.optLong("dur", 0L),
                            pointIndex = obj.optInt("idx", 0),
                            distanceMeters = obj.optDouble("dist", 0.0)
                        )
                    )
                }
            } catch (_: Exception) {}
            return list
        }

        internal fun placesToJson(places: List<VisitedPlace>): String {
            val array = JSONArray()
            for (p in places) {
                val obj = JSONObject().apply {
                    put("name", p.placeName)
                    put("sub", p.hierarchySubtitle)
                    put("time", p.timestamp)
                    put("lat", p.latitude)
                    put("lng", p.longitude)
                    put("dist", p.distanceAtEntryMeters)
                    put("kind", p.placeKind.name)
                }
                array.put(obj)
            }
            return array.toString()
        }

        internal fun jsonToPlaces(jsonStr: String): List<VisitedPlace> {
            val list = mutableListOf<VisitedPlace>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val kindStr = obj.optString("kind", PlaceKind.LOCALITY.name)
                    val kind = try { PlaceKind.valueOf(kindStr) } catch (_: Exception) { PlaceKind.LOCALITY }
                    val rawName = obj.getString("name")
                    val rawSub = obj.optString("sub", "")
                    val resolvedName = if (rawName.equals("Krzemionki", ignoreCase = true) &&
                        (rawSub.contains("gm. Kozy", ignoreCase = true) || rawSub.contains("gmina Kozy", ignoreCase = true))) {
                        "Kozy"
                    } else rawName

                    val place = VisitedPlace(
                        placeName = resolvedName,
                        hierarchySubtitle = rawSub,
                        timestamp = obj.getLong("time"),
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lng"),
                        distanceAtEntryMeters = obj.getDouble("dist"),
                        placeKind = kind
                    )
                    if (list.isNotEmpty() && list.last().placeName == place.placeName && list.last().placeKind == place.placeKind) {
                        continue
                    }
                    list.add(place)
                }
            } catch (_: Exception) {}
            return list
        }

        internal fun pointsToJson(points: List<GeoPoint>, sources: List<String>? = null, defaultSource: String? = null): String {
            val array = JSONArray()
            for (i in points.indices) {
                val p = points[i]
                val obj = JSONObject().apply {
                    put("lat", p.latitude)
                    put("lng", p.longitude)
                    val alt = p.altitude
                    if (!alt.isNaN() && alt != 0.0) {
                        put("alt", alt)
                        val src = sources?.getOrNull(i) ?: defaultSource
                        if (src != null) {
                            put("src", src)
                        }
                    }
                }
                array.put(obj)
            }
            return array.toString()
        }

        internal fun jsonToPoints(jsonStr: String): List<GeoPoint> {
            val list = mutableListOf<GeoPoint>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lng = obj.getDouble("lng")
                    val alt = if (obj.has("alt") && !obj.isNull("alt")) obj.getDouble("alt") else 0.0
                    list.add(if (alt != 0.0 && !alt.isNaN()) GeoPoint(lat, lng, alt) else GeoPoint(lat, lng))
                }
            } catch (_: Exception) {}
            return list
        }

        /** Legacy points_json -> rows (backfill). Legacy points carry no time/speed/accuracy. */
        internal fun jsonToRows(jsonStr: String): List<TrackPointRow> {
            val rows = ArrayList<TrackPointRow>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val alt = if (obj.has("alt") && !obj.isNull("alt")) obj.getDouble("alt") else 0.0
                    val hasAlt = alt != 0.0 && !alt.isNaN()
                    rows.add(
                        TrackPointRow(
                            tUtcMs = null,
                            lat = obj.getDouble("lat"),
                            lng = obj.getDouble("lng"),
                            altitudeM = if (hasAlt) alt else null,
                            altSource = if (hasAlt) obj.optString("src", "GPS") else null,
                            speedMps = null,
                            hAccM = null,
                            bearingDeg = null
                        )
                    )
                }
            } catch (_: Exception) {}
            return rows
        }

        internal fun jsonToPointsWithSources(jsonStr: String): Pair<List<GeoPoint>, List<String>> {
            val pts = mutableListOf<GeoPoint>()
            val srcs = mutableListOf<String>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lng = obj.getDouble("lng")
                    val alt = if (obj.has("alt") && !obj.isNull("alt")) obj.getDouble("alt") else 0.0
                    val src = obj.optString("src", "GPS")
                    pts.add(if (alt != 0.0 && !alt.isNaN()) GeoPoint(lat, lng, alt) else GeoPoint(lat, lng))
                    srcs.add(src)
                }
            } catch (_: Exception) {}
            return Pair(pts, srcs)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTripsTable = """
            CREATE TABLE $TABLE_TRIPS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT DEFAULT '',
                $COL_START_TIME INTEGER NOT NULL,
                $COL_END_TIME INTEGER,
                $COL_DISTANCE REAL NOT NULL,
                $COL_MAX_SPEED REAL NOT NULL,
                $COL_AVG_SPEED REAL NOT NULL,
                $COL_IS_AUTO INTEGER NOT NULL,
                $COL_ACTIVITY_PROFILE TEXT DEFAULT 'CAR',
                $COL_POINTS_JSON TEXT,
                $COL_PLACES_JSON TEXT,
                $COL_PAUSES_JSON TEXT DEFAULT '[]',
                $COL_POINTS_STORE INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent()
        db.execSQL(createTripsTable)
        createTrackPointsTable(db)

        createSavedPlacesTable(db)
        createElevationCacheTable(db)
    }

    /** Alternate (road-snapped) geometry; the recorded track in track_points stays authoritative. */
    private fun createTripMatchesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRIP_MATCHES (
                trip_id INTEGER PRIMARY KEY,
                state INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_attempt_ms INTEGER NOT NULL DEFAULT 0,
                geometry TEXT
            )
            """.trimIndent()
        )
    }

    private fun createTrackPointsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRACK_POINTS (
                $COL_TP_TRIP INTEGER NOT NULL,
                $COL_TP_SEQ INTEGER NOT NULL,
                $COL_TP_T INTEGER,
                $COL_TP_LAT REAL NOT NULL,
                $COL_TP_LNG REAL NOT NULL,
                $COL_TP_ALT REAL,
                $COL_TP_ALT_SRC TEXT,
                $COL_TP_SPEED REAL,
                $COL_TP_HACC REAL,
                $COL_TP_BEARING REAL,
                $COL_TP_FLAGS INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY ($COL_TP_TRIP, $COL_TP_SEQ)
            ) WITHOUT ROWID
            """.trimIndent()
        )
    }

    /** Idempotent: safe to call on every open (also repairs an interrupted v9 upgrade). */
    private fun ensureTrackPointsSchema(db: SQLiteDatabase) {
        createTrackPointsTable(db)
        createTripMatchesTable(db)
        val hasCol = db.rawQuery("PRAGMA table_info($TABLE_TRIPS)", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            var found = false
            while (c.moveToNext()) if (c.getString(nameIdx) == COL_POINTS_STORE) found = true
            found
        }
        if (!hasCol) {
            db.execSQL("ALTER TABLE $TABLE_TRIPS ADD COLUMN $COL_POINTS_STORE INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Copy legacy points_json into track_points for trips not yet migrated. points_json is left
     * untouched (read-only) so the original data always survives. One transaction per trip: a crash
     * leaves each trip either fully migrated or untouched.
     */
    private fun backfillTrackPoints(db: SQLiteDatabase) {
        try {
            val pending = ArrayList<Long>()
            db.rawQuery("SELECT $COL_ID FROM $TABLE_TRIPS WHERE $COL_POINTS_STORE = 0", null).use { c ->
                while (c.moveToNext()) pending.add(c.getLong(0))
            }
            for (id in pending) {
                val json = db.rawQuery("SELECT $COL_POINTS_JSON FROM $TABLE_TRIPS WHERE $COL_ID = ?", arrayOf(id.toString())).use { c ->
                    if (c.moveToFirst()) c.getString(0) ?: "[]" else "[]"
                }
                val rows = jsonToRows(json)
                db.beginTransaction()
                try {
                    db.delete(TABLE_TRACK_POINTS, "$COL_TP_TRIP = ?", arrayOf(id.toString()))
                    insertRows(db, id, 0, rows)
                    db.execSQL("UPDATE $TABLE_TRIPS SET $COL_POINTS_STORE = 1 WHERE $COL_ID = ?", arrayOf<Any>(id))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            if (pending.isNotEmpty()) {
                TelemetryLogger.log("DB_MIGRATION", "Backfilled track_points for ${pending.size} legacy trips")
            }
        } catch (e: Exception) {
            TelemetryLogger.log("DB_MIGRATION", "track_points backfill failed (will retry on next open): ${e.message}")
        }
    }

    private fun insertRows(db: SQLiteDatabase, tripId: Long, startSeq: Int, rows: List<TrackPointRow>) {
        if (rows.isEmpty()) return
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO $TABLE_TRACK_POINTS " +
                "($COL_TP_TRIP,$COL_TP_SEQ,$COL_TP_T,$COL_TP_LAT,$COL_TP_LNG,$COL_TP_ALT,$COL_TP_ALT_SRC," +
                "$COL_TP_SPEED,$COL_TP_HACC,$COL_TP_BEARING,$COL_TP_FLAGS) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        )
        try {
            var seq = startSeq
            for (r in rows) {
                stmt.clearBindings()
                stmt.bindLong(1, tripId)
                stmt.bindLong(2, seq.toLong())
                if (r.tUtcMs != null) stmt.bindLong(3, r.tUtcMs)
                stmt.bindDouble(4, r.lat)
                stmt.bindDouble(5, r.lng)
                if (r.altitudeM != null) stmt.bindDouble(6, r.altitudeM)
                if (r.altSource != null) stmt.bindString(7, r.altSource)
                if (r.speedMps != null) stmt.bindDouble(8, r.speedMps.toDouble())
                if (r.hAccM != null) stmt.bindDouble(9, r.hAccM.toDouble())
                if (r.bearingDeg != null) stmt.bindDouble(10, r.bearingDeg.toDouble())
                stmt.bindLong(11, r.flags.toLong())
                stmt.executeInsert()
                seq++
            }
        } finally {
            stmt.close()
        }
    }

    /** Append rows for an active trip. [startSeq] is the index of the first row (= points already stored). */
    fun appendTrackPoints(tripId: Long, startSeq: Int, rows: List<TrackPointRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertRows(db, tripId, startSeq, rows)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceTrackPoints(tripId: Long, rows: List<TrackPointRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_TRACK_POINTS, "$COL_TP_TRIP = ?", arrayOf(tripId.toString()))
            insertRows(db, tripId, 0, rows)
            db.execSQL("UPDATE $TABLE_TRIPS SET $COL_POINTS_STORE = 1 WHERE $COL_ID = ?", arrayOf<Any>(tripId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Newest `t_utc` for an open trip, or null when the table has no timed points. */
    fun getLastTrackPointUtcMs(tripId: Long): Long? {
        val db = readableDatabase
        return db.rawQuery(
            "SELECT MAX($COL_TP_T) FROM $TABLE_TRACK_POINTS WHERE $COL_TP_TRIP = ?",
            arrayOf(tripId.toString())
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    fun getTrackPointRows(tripId: Long): List<TrackPointRow> {
        val db = readableDatabase
        val store = db.rawQuery("SELECT $COL_POINTS_STORE FROM $TABLE_TRIPS WHERE $COL_ID = ?", arrayOf(tripId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 1
        }
        if (store == 0) {
            val json = db.rawQuery("SELECT $COL_POINTS_JSON FROM $TABLE_TRIPS WHERE $COL_ID = ?", arrayOf(tripId.toString())).use {
                if (it.moveToFirst()) it.getString(0) ?: "[]" else "[]"
            }
            return jsonToRows(json)
        }
        val rows = ArrayList<TrackPointRow>()
        db.rawQuery(
            "SELECT $COL_TP_T,$COL_TP_LAT,$COL_TP_LNG,$COL_TP_ALT,$COL_TP_ALT_SRC,$COL_TP_SPEED,$COL_TP_HACC,$COL_TP_BEARING,$COL_TP_FLAGS " +
                "FROM $TABLE_TRACK_POINTS WHERE $COL_TP_TRIP = ? ORDER BY $COL_TP_SEQ",
            arrayOf(tripId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    TrackPointRow(
                        tUtcMs = if (c.isNull(0)) null else c.getLong(0),
                        lat = c.getDouble(1),
                        lng = c.getDouble(2),
                        altitudeM = if (c.isNull(3)) null else c.getDouble(3),
                        altSource = if (c.isNull(4)) null else c.getString(4),
                        speedMps = if (c.isNull(5)) null else c.getFloat(5),
                        hAccM = if (c.isNull(6)) null else c.getFloat(6),
                        bearingDeg = if (c.isNull(7)) null else c.getFloat(7),
                        flags = c.getInt(8)
                    )
                )
            }
        }
        return rows
    }

    private fun rowsToGeoPoints(rows: List<TrackPointRow>): List<GeoPoint> = rows.map {
        val alt = it.altitudeM
        if (alt != null && !alt.isNaN() && alt != 0.0) GeoPoint(it.lat, it.lng, alt) else GeoPoint(it.lat, it.lng)
    }

    private fun countTrackPoints(db: SQLiteDatabase, tripId: Long, legacyJson: String): Int {
        val store = db.rawQuery("SELECT $COL_POINTS_STORE FROM $TABLE_TRIPS WHERE $COL_ID = ?", arrayOf(tripId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 1
        }
        if (store == 0) return countPointsFromJson(legacyJson)
        return db.rawQuery("SELECT COUNT(*) FROM $TABLE_TRACK_POINTS WHERE $COL_TP_TRIP = ?", arrayOf(tripId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun createSavedPlacesTable(db: SQLiteDatabase) {
        val createPlacesTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_SAVED_PLACES (
                $COL_SP_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SP_NAME TEXT NOT NULL,
                $COL_SP_CATEGORY TEXT NOT NULL,
                $COL_SP_LAT REAL NOT NULL,
                $COL_SP_LNG REAL NOT NULL,
                $COL_SP_RADIUS REAL DEFAULT 100.0,
                $COL_SP_LOCALITY TEXT DEFAULT '',
                $COL_SP_STREET TEXT DEFAULT '',
                $COL_SP_CREATED_AT INTEGER NOT NULL,
                $COL_SP_COLOR TEXT DEFAULT '',
                $COL_SP_ALTITUDE REAL DEFAULT NULL,
                $COL_SP_ALTITUDE_SOURCE TEXT DEFAULT NULL
            )
        """.trimIndent()
        db.execSQL(createPlacesTable)
    }

    private fun createElevationCacheTable(db: SQLiteDatabase) {
        val createCacheTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_ELEVATION_CACHE (
                $COL_EC_GRID_KEY TEXT PRIMARY KEY,
                $COL_EC_ELEVATION REAL NOT NULL,
                $COL_EC_UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createCacheTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRIPS ADD COLUMN $COL_TITLE TEXT DEFAULT ''")
            } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            try {
                createSavedPlacesTable(db)
            } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRIPS ADD COLUMN $COL_ACTIVITY_PROFILE TEXT DEFAULT 'CAR'")
            } catch (_: Exception) {}
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRIPS ADD COLUMN $COL_PAUSES_JSON TEXT DEFAULT '[]'")
            } catch (_: Exception) {}
        }
        if (oldVersion < 6) {
            // Version 6: points_json optionally carries 'alt' (altitude in meters).
            // TEXT column stores JSON, no DDL alteration needed.
        }
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE $TABLE_SAVED_PLACES ADD COLUMN $COL_SP_ALTITUDE REAL DEFAULT NULL")
            } catch (_: Exception) {}
        }
        if (oldVersion < 8) {
            try {
                db.execSQL("ALTER TABLE $TABLE_SAVED_PLACES ADD COLUMN $COL_SP_ALTITUDE_SOURCE TEXT DEFAULT NULL")
            } catch (_: Exception) {}
            try {
                createElevationCacheTable(db)
            } catch (_: Exception) {}
        }
        if (oldVersion < 9) {
            // v9: dedicated track_points table. Schema only here; the data copy runs idempotently in onOpen.
            try {
                ensureTrackPointsSchema(db)
            } catch (_: Exception) {}
        }
        if (oldVersion < 10) {
            // v10: trip_matches (post-trip OSRM alternate geometry).
            try {
                createTripMatchesTable(db)
            } catch (_: Exception) {}
        }
        if (oldVersion < 11) {
            // v11: every match up to v10 was rejected by the public server's 10-coordinate limit; retry them.
            try {
                db.execSQL("DELETE FROM $TABLE_TRIP_MATCHES WHERE state = $MATCH_REJECTED")
            } catch (_: Exception) {}
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureTrackPointsSchema(db)
        backfillTrackPoints(db)
        cleanUpTrailingDestinationPauses(db)
        ensureSavedPlacesColumns(db)
        sanitizeStoredPlaces(db)
    }

    private fun sanitizeStoredPlaces(db: SQLiteDatabase) {
        try {
            val cursor = db.rawQuery(
                "SELECT $COL_ID, $COL_PLACES_JSON FROM $TABLE_TRIPS WHERE $COL_PLACES_JSON LIKE '%Krzemionki%'",
                null
            )
            val updates = mutableListOf<Pair<Long, String>>()
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(COL_ID)
                val placesIdx = c.getColumnIndexOrThrow(COL_PLACES_JSON)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val rawPlacesJson = c.getString(placesIdx) ?: "[]"
                    val parsed = jsonToPlaces(rawPlacesJson)
                    val cleanedJson = placesToJson(parsed)
                    if (cleanedJson != rawPlacesJson) {
                        updates.add(Pair(id, cleanedJson))
                    }
                }
            }
            if (updates.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for ((tripId, newPlaces) in updates) {
                        val cv = ContentValues().apply { put(COL_PLACES_JSON, newPlaces) }
                        db.update(TABLE_TRIPS, cv, "$COL_ID = ?", arrayOf(tripId.toString()))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } catch (_: Exception) {}
    }

    private fun ensureSavedPlacesColumns(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE $TABLE_SAVED_PLACES ADD COLUMN $COL_SP_COLOR TEXT DEFAULT ''")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE $TABLE_SAVED_PLACES ADD COLUMN $COL_SP_ALTITUDE REAL DEFAULT NULL")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE $TABLE_SAVED_PLACES ADD COLUMN $COL_SP_ALTITUDE_SOURCE TEXT DEFAULT NULL")
        } catch (_: Exception) {}
        try {
            createElevationCacheTable(db)
        } catch (_: Exception) {}
    }

    private fun cleanUpTrailingDestinationPauses(db: SQLiteDatabase) {
        try {
            val cursor = db.rawQuery(
                "SELECT $COL_ID, $COL_END_TIME, $COL_POINTS_JSON, $COL_PAUSES_JSON FROM $TABLE_TRIPS WHERE $COL_PAUSES_JSON IS NOT NULL AND $COL_PAUSES_JSON != '[]'",
                null
            )
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(COL_ID)
                val endIdx = c.getColumnIndexOrThrow(COL_END_TIME)
                val ptsIdx = c.getColumnIndexOrThrow(COL_POINTS_JSON)
                val pausesIdx = c.getColumnIndexOrThrow(COL_PAUSES_JSON)

                while (c.moveToNext()) {
                    val tripId = c.getLong(idIdx)
                    val endTime = if (!c.isNull(endIdx)) c.getLong(endIdx) else null
                    val pointsJson = c.getString(ptsIdx) ?: "[]"
                    val pausesJson = c.getString(pausesIdx) ?: "[]"

                    val pauses = jsonToPauses(pausesJson)
                    if (pauses.isEmpty()) continue

                    val last = pauses.last()
                    val totalPoints = countTrackPoints(db, tripId, pointsJson)
                    val isAtEnd = last.pointIndex >= (totalPoints - 2).coerceAtLeast(0)
                    val endsNearTripEnd = endTime != null && ((endTime - (last.endTime ?: last.startTime)) <= 45_000L || (last.endTime ?: 0L) >= endTime)

                    if (isAtEnd || endsNearTripEnd) {
                        val sanitized = pauses.dropLast(1)
                        val updatedJson = pausesToJson(sanitized)
                        val cv = ContentValues().apply {
                            put(COL_PAUSES_JSON, updatedJson)
                        }
                        db.update(TABLE_TRIPS, cv, "$COL_ID = ?", arrayOf(tripId.toString()))
                        TelemetryLogger.log("DB_MIGRATION", "Trimmed trailing arrival pause from Trip $tripId: dur=${last.durationMs / 1000}s, idx=${last.pointIndex}/$totalPoints")
                    }
                }
            }
        } catch (e: Exception) {
            TelemetryLogger.log("DB_MIGRATION", "Error during cleanUpTrailingDestinationPauses: ${e.message}")
        }
    }

    private fun countPointsFromJson(jsonStr: String): Int {
        return try {
            JSONArray(jsonStr).length()
        } catch (_: Exception) {
            0
        }
    }

    private fun tripValues(trip: TripRecord): ContentValues = ContentValues().apply {
        put(COL_TITLE, trip.title)
        put(COL_START_TIME, trip.startTime)
        put(COL_END_TIME, trip.endTime)
        put(COL_DISTANCE, trip.distanceMeters)
        put(COL_MAX_SPEED, trip.maxSpeedKmh)
        put(COL_AVG_SPEED, trip.avgSpeedKmh)
        put(COL_IS_AUTO, if (trip.isAutoDetected) 1 else 0)
        put(COL_ACTIVITY_PROFILE, trip.activityProfile.name)
        put(COL_PLACES_JSON, placesToJson(trip.placesVisited))
        put(COL_PAUSES_JSON, pausesToJson(trip.pauses))
    }

    /**
     * Insert a trip. Geometry goes to track_points ([rows] when given, otherwise derived from
     * [TripRecord.points] / [pointSources]); points_json stays empty for new trips.
     */
    fun insertTrip(trip: TripRecord, pointSources: List<String>? = null, rows: List<TrackPointRow>? = null): Long {
        val db = writableDatabase
        val values = tripValues(trip).apply {
            put(COL_POINTS_JSON, "[]")
            put(COL_POINTS_STORE, 1)
        }
        val id = db.insert(TABLE_TRIPS, null, values)
        if (id > 0L) {
            val toWrite = rows ?: pointsToRows(emptyList(), trip.points, pointSources)
            if (toWrite.isNotEmpty()) {
                db.beginTransaction()
                try {
                    insertRows(db, id, 0, toWrite)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        return id
    }

    private fun pointsToRows(existing: List<TrackPointRow>, points: List<GeoPoint>, sources: List<String>?): List<TrackPointRow> {
        val lats = DoubleArray(points.size) { points[it].latitude }
        val lngs = DoubleArray(points.size) { points[it].longitude }
        val alts = points.map { it.altitude }
        return TrackPointRow.rebind(existing, lats, lngs, alts, sources)
    }

    /** Metadata only (stats, places, pauses, title). Geometry is untouched. */
    fun updateTripMeta(trip: TripRecord) {
        val db = writableDatabase
        db.update(TABLE_TRIPS, tripValues(trip), "$COL_ID = ?", arrayOf(trip.id.toString()))
    }

    /** Metadata plus a full geometry rewrite (split, elevation correction). Preserves per-point metadata where aligned. */
    fun updateTrip(trip: TripRecord, pointSources: List<String>? = null) {
        updateTripMeta(trip)
        val existing = getTrackPointRows(trip.id)
        replaceTrackPoints(trip.id, pointsToRows(existing, trip.points, pointSources))
    }

    fun getTripPointsWithSources(id: Long): Pair<List<GeoPoint>, List<String>> {
        val rows = getTrackPointRows(id)
        return Pair(rowsToGeoPoints(rows), rows.map { it.altSource ?: "GPS" })
    }

    fun updateTripActivityProfile(id: Long, profile: ActivityProfile) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ACTIVITY_PROFILE, profile.name)
        }
        db.update(TABLE_TRIPS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun renameTrip(id: Long, newTitle: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, newTitle)
        }
        db.update(TABLE_TRIPS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    private fun cursorToTripRecord(cursor: android.database.Cursor): TripRecord {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
        val titleColIdx = cursor.getColumnIndex(COL_TITLE)
        val title = if (titleColIdx >= 0 && !cursor.isNull(titleColIdx)) cursor.getString(titleColIdx) else ""
        val startTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_START_TIME))
        val endTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_END_TIME))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_END_TIME))
        val distance = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DISTANCE))
        val maxSpeed = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_MAX_SPEED))
        val avgSpeed = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_AVG_SPEED))
        val isAuto = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_AUTO)) == 1
        val profileColIdx = cursor.getColumnIndex(COL_ACTIVITY_PROFILE)
        val profile = if (profileColIdx >= 0 && !cursor.isNull(profileColIdx)) {
            try { ActivityProfile.valueOf(cursor.getString(profileColIdx)) } catch (_: Exception) { ActivityProfile.CAR }
        } else {
            ActivityProfile.CAR
        }
        val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_POINTS_JSON)) ?: "[]"
        val storeIdx = cursor.getColumnIndex(COL_POINTS_STORE)
        val pointsFromTable = storeIdx >= 0 && !cursor.isNull(storeIdx) && cursor.getInt(storeIdx) == 1
        val placesJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_PLACES_JSON)) ?: "[]"
        val pausesColIdx = cursor.getColumnIndex(COL_PAUSES_JSON)
        val pausesJson = if (pausesColIdx >= 0 && !cursor.isNull(pausesColIdx)) cursor.getString(pausesColIdx) ?: "[]" else "[]"

        return TripRecord(
            id = id,
            title = title,
            startTime = startTime,
            endTime = endTime,
            distanceMeters = distance,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            isAutoDetected = isAuto,
            activityProfile = profile,
            points = if (pointsFromTable) rowsToGeoPoints(getTrackPointRows(id)) else jsonToPoints(pointsJson),
            placesVisited = jsonToPlaces(placesJson),
            pauses = jsonToPauses(pausesJson)
        )
    }

    fun getAllTrips(): List<TripRecord> {
        val trips = mutableListOf<TripRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRIPS,
            null,
            null,
            null,
            null,
            null,
            "$COL_START_TIME DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                trips.add(cursorToTripRecord(it))
            }
        }
        return trips
    }

    fun queryTrips(
        searchQuery: String? = null,
        startTimeMin: Long? = null,
        startTimeMax: Long? = null,
        activityProfile: ActivityProfile? = null
    ): List<TripRecord> {
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (!searchQuery.isNullOrBlank()) {
            selectionParts.add("($COL_TITLE LIKE ? OR $COL_PLACES_JSON LIKE ?)")
            val wild = "%${searchQuery.trim()}%"
            selectionArgs.add(wild)
            selectionArgs.add(wild)
        }

        if (startTimeMin != null) {
            selectionParts.add("$COL_START_TIME >= ?")
            selectionArgs.add(startTimeMin.toString())
        }

        if (startTimeMax != null) {
            selectionParts.add("$COL_START_TIME < ?")
            selectionArgs.add(startTimeMax.toString())
        }

        if (activityProfile != null) {
            selectionParts.add("$COL_ACTIVITY_PROFILE = ?")
            selectionArgs.add(activityProfile.name)
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val trips = mutableListOf<TripRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRIPS,
            null,
            selection,
            args,
            null,
            null,
            "$COL_START_TIME DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                trips.add(cursorToTripRecord(it))
            }
        }
        return trips
    }

    fun getActiveOrUnclosedTrip(): TripRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRIPS,
            null,
            "$COL_END_TIME IS NULL",
            null,
            null,
            null,
            "$COL_START_TIME DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToTripRecord(it)
            }
        }
        return null
    }

    fun deleteTrip(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_TRACK_POINTS, "$COL_TP_TRIP = ?", arrayOf(id.toString()))
            db.delete(TABLE_TRIP_MATCHES, "trip_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_TRIPS, "$COL_ID = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getTripsByIds(ids: List<Long>): List<TripRecord> {
        if (ids.isEmpty()) return emptyList()
        val trips = mutableListOf<TripRecord>()
        val db = readableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        val cursor = db.query(
            TABLE_TRIPS,
            null,
            "$COL_ID IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
            null,
            null,
            "$COL_START_TIME ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                trips.add(cursorToTripRecord(it))
            }
        }
        return trips
    }

    fun mergeTrips(
        tripIds: List<Long>,
        customTitle: String? = null,
        targetProfile: ActivityProfile? = null
    ): Long {
        if (tripIds.size < 2) return tripIds.firstOrNull() ?: 0L
        val tripsToMerge = getTripsByIds(tripIds)
        if (tripsToMerge.isEmpty()) return 0L

        val sorted = tripsToMerge.sortedBy { it.startTime }
        val earliest = sorted.first()
        val latest = sorted.last()

        val allPoints = mutableListOf<GeoPoint>()
        val allPlaces = mutableListOf<VisitedPlace>()
        val allPauses = mutableListOf<TripPause>()
        var totalDist = 0.0
        var maxSpd = 0f
        var totalDurationSec = 0.0
        var weightedSpeedSum = 0.0
        var cumulativeDistanceOffset = 0.0

        for (i in sorted.indices) {
            val trip = sorted[i]
            val pointOffset = allPoints.size

            // 1. Preserve existing pauses from this trip, shifting pointIndex by pointOffset AND shifting distanceMeters
            for (pause in trip.pauses) {
                allPauses.add(
                    pause.copy(
                        pointIndex = pause.pointIndex + pointOffset,
                        distanceMeters = cumulativeDistanceOffset + pause.distanceMeters
                    )
                )
            }

            allPoints.addAll(trip.points)

            // 2. Add visited places with recalculated distance from the start of the merged trip
            for (p in trip.placesVisited) {
                allPlaces.add(
                    p.copy(distanceAtEntryMeters = cumulativeDistanceOffset + p.distanceAtEntryMeters)
                )
            }

            // 3. Mark the inter-trip time gap as a rest stop (TripPause)
            if (i < sorted.size - 1) {
                val nextTrip = sorted[i + 1]
                val gapStart = trip.endTime ?: (trip.startTime + 60_000L)
                val gapEnd = nextTrip.startTime
                val gapDuration = (gapEnd - gapStart).coerceAtLeast(0L)

                if (gapDuration >= 10_000L) {
                    val pauseLat = trip.points.lastOrNull()?.latitude
                        ?: nextTrip.points.firstOrNull()?.latitude
                        ?: 0.0
                    val pauseLng = trip.points.lastOrNull()?.longitude
                        ?: nextTrip.points.firstOrNull()?.longitude
                        ?: 0.0
                    val junctionIndex = (allPoints.size - 1).coerceAtLeast(0)

                    allPauses.add(
                        TripPause(
                            startTime = gapStart,
                            endTime = gapEnd,
                            latitude = pauseLat,
                            longitude = pauseLng,
                            durationMs = gapDuration,
                            pointIndex = junctionIndex,
                            distanceMeters = cumulativeDistanceOffset + trip.distanceMeters
                        )
                    )
                }
            }

            cumulativeDistanceOffset += trip.distanceMeters
            totalDist += trip.distanceMeters
            maxSpd = maxOf(maxSpd, trip.maxSpeedKmh)
            val dur = (((trip.endTime ?: (trip.startTime + 60000L)) - trip.startTime) / 1000.0).coerceAtLeast(1.0)
            totalDurationSec += dur
            weightedSpeedSum += (trip.avgSpeedKmh * dur)
        }

        val totalDurationMs = ((latest.endTime ?: latest.startTime) - earliest.startTime).coerceAtLeast(0L)
        val pauseDurationMs = allPauses.sumOf { it.durationMs }
        val movingDurationHours = (totalDurationMs - pauseDurationMs).coerceAtLeast(1_000L) / 3600000.0
        val overallAvgSpeed = if (movingDurationHours > 0.0 && totalDist > 0.0) {
            ((totalDist / 1000.0) / movingDurationHours).toFloat().coerceIn(0f, 250f)
        } else if (totalDurationSec > 0) {
            (weightedSpeedSum / totalDurationSec).toFloat()
        } else {
            earliest.avgSpeedKmh
        }

        val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(earliest.startTime))
        val defaultTitle = if (earliest.title.isNotBlank()) "${earliest.title} (Merged)" else "$dateStr • Merged Trips (${sorted.size})"
        val finalTitle = customTitle?.takeIf { it.isNotBlank() } ?: defaultTitle
        val finalProfile = targetProfile ?: earliest.activityProfile

        val mergedTrip = TripRecord(
            title = finalTitle,
            startTime = earliest.startTime,
            endTime = latest.endTime ?: latest.startTime,
            distanceMeters = totalDist,
            maxSpeedKmh = maxSpd,
            avgSpeedKmh = overallAvgSpeed,
            isAutoDetected = earliest.isAutoDetected,
            activityProfile = finalProfile,
            points = allPoints,
            placesVisited = allPlaces,
            pauses = allPauses.sortedBy { it.startTime }
        )

        val mergedRows = sorted.flatMap { getTrackPointRows(it.id) }
        val newId = insertTrip(mergedTrip, rows = mergedRows)

        // Remove original fragmented trips
        val db = writableDatabase
        val placeholders = tripIds.joinToString(",") { "?" }
        val idArgs = tripIds.map { it.toString() }.toTypedArray()
        db.delete(TABLE_TRACK_POINTS, "$COL_TP_TRIP IN ($placeholders)", idArgs)
        db.delete(TABLE_TRIP_MATCHES, "trip_id IN ($placeholders)", idArgs)
        db.delete(TABLE_TRIPS, "$COL_ID IN ($placeholders)", idArgs)

        return newId
    }

    // ── Post-trip road matching (alternate geometry) ───────────────────────────

    /** CAR trips that finished, have no match yet (or a retryable pending one). */
    fun pendingMatchTripIds(limit: Int, nowMs: Long, retryAfterMs: Long): List<Long> {
        val ids = ArrayList<Long>()
        readableDatabase.rawQuery(
            """
            SELECT t.$COL_ID FROM $TABLE_TRIPS t LEFT JOIN $TABLE_TRIP_MATCHES m ON m.trip_id = t.$COL_ID
            WHERE t.$COL_END_TIME IS NOT NULL AND t.$COL_ACTIVITY_PROFILE = 'CAR'
              AND (m.trip_id IS NULL OR (m.state = $MATCH_PENDING AND m.attempts < $MATCH_MAX_ATTEMPTS AND m.last_attempt_ms < ?))
            ORDER BY t.$COL_START_TIME DESC LIMIT ?
            """.trimIndent(),
            arrayOf((nowMs - retryAfterMs).toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    /**
     * @param countAttempt false for transient failures (offline, rate limit) so they never exhaust the retry budget.
     */
    fun saveMatchResult(tripId: Long, state: Int, geometryJson: String?, countAttempt: Boolean, nowMs: Long) {
        val db = writableDatabase
        val prev = db.rawQuery("SELECT attempts FROM $TABLE_TRIP_MATCHES WHERE trip_id = ?", arrayOf(tripId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val v = android.content.ContentValues().apply {
            put("trip_id", tripId)
            put("state", state)
            put("attempts", if (countAttempt) prev + 1 else prev)
            put("last_attempt_ms", nowMs)
            if (geometryJson != null) put("geometry", geometryJson) else putNull("geometry")
        }
        db.insertWithOnConflict(TABLE_TRIP_MATCHES, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun invalidateMatch(tripId: Long) {
        writableDatabase.delete(TABLE_TRIP_MATCHES, "trip_id = ?", arrayOf(tripId.toString()))
    }

    /** Road-snapped alternate geometry, or null when absent / rejected / pending. */
    fun getMatchedGeometry(tripId: Long): List<GeoPoint>? {
        val json = readableDatabase.rawQuery(
            "SELECT geometry FROM $TABLE_TRIP_MATCHES WHERE trip_id = ? AND state = $MATCH_DONE", arrayOf(tripId.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        return janush.tech.whereami.tracking.RouteMatching.geometryFromJson(json)
    }

    // ── Saved Places CRUD ──────────────────────────────────────────────────────
    fun insertSavedPlace(place: SavedPlace): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SP_NAME, place.name)
            put(COL_SP_CATEGORY, place.category.name)
            put(COL_SP_LAT, place.latitude)
            put(COL_SP_LNG, place.longitude)
            put(COL_SP_RADIUS, place.radiusMeters)
            put(COL_SP_LOCALITY, place.locality)
            put(COL_SP_STREET, place.street)
            put(COL_SP_CREATED_AT, place.createdAt)
            put(COL_SP_COLOR, place.colorHex)
            if (place.altitude != null) {
                put(COL_SP_ALTITUDE, place.altitude)
            } else {
                putNull(COL_SP_ALTITUDE)
            }
            if (place.altitudeSource != null) {
                put(COL_SP_ALTITUDE_SOURCE, place.altitudeSource)
            } else {
                putNull(COL_SP_ALTITUDE_SOURCE)
            }
        }
        return db.insert(TABLE_SAVED_PLACES, null, values)
    }

    fun updateSavedPlace(place: SavedPlace) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SP_NAME, place.name)
            put(COL_SP_CATEGORY, place.category.name)
            put(COL_SP_LAT, place.latitude)
            put(COL_SP_LNG, place.longitude)
            put(COL_SP_RADIUS, place.radiusMeters)
            put(COL_SP_LOCALITY, place.locality)
            put(COL_SP_STREET, place.street)
            put(COL_SP_COLOR, place.colorHex)
            if (place.altitude != null) {
                put(COL_SP_ALTITUDE, place.altitude)
            } else {
                putNull(COL_SP_ALTITUDE)
            }
            if (place.altitudeSource != null) {
                put(COL_SP_ALTITUDE_SOURCE, place.altitudeSource)
            } else {
                putNull(COL_SP_ALTITUDE_SOURCE)
            }
        }
        db.update(TABLE_SAVED_PLACES, values, "$COL_SP_ID = ?", arrayOf(place.id.toString()))
    }

    fun deleteSavedPlace(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_SAVED_PLACES, "$COL_SP_ID = ?", arrayOf(id.toString()))
    }

    fun getAllSavedPlaces(): List<SavedPlace> {
        val list = mutableListOf<SavedPlace>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SAVED_PLACES,
            null,
            null,
            null,
            null,
            null,
            "$COL_SP_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_SP_ID))
                val name = it.getString(it.getColumnIndexOrThrow(COL_SP_NAME))
                val catStr = it.getString(it.getColumnIndexOrThrow(COL_SP_CATEGORY))
                val lat = it.getDouble(it.getColumnIndexOrThrow(COL_SP_LAT))
                val lng = it.getDouble(it.getColumnIndexOrThrow(COL_SP_LNG))
                val radius = it.getFloat(it.getColumnIndexOrThrow(COL_SP_RADIUS))
                val locality = it.getString(it.getColumnIndexOrThrow(COL_SP_LOCALITY)) ?: ""
                val street = it.getString(it.getColumnIndexOrThrow(COL_SP_STREET)) ?: ""
                val createdAt = it.getLong(it.getColumnIndexOrThrow(COL_SP_CREATED_AT))
                val colorIdx = it.getColumnIndex(COL_SP_COLOR)
                val colorHex = if (colorIdx >= 0) it.getString(colorIdx) ?: "" else ""
                val altIdx = it.getColumnIndex(COL_SP_ALTITUDE)
                val altitude = if (altIdx >= 0 && !it.isNull(altIdx)) it.getDouble(altIdx) else null
                val altSrcIdx = it.getColumnIndex(COL_SP_ALTITUDE_SOURCE)
                val altitudeSource = if (altSrcIdx >= 0 && !it.isNull(altSrcIdx)) it.getString(altSrcIdx) else null

                val category = try {
                    PlaceCategory.valueOf(catStr)
                } catch (_: Exception) {
                    PlaceCategory.CUSTOM
                }

                list.add(
                    SavedPlace(
                        id = id,
                        name = name,
                        category = category,
                        latitude = lat,
                        longitude = lng,
                        radiusMeters = radius,
                        locality = locality,
                        street = street,
                        createdAt = createdAt,
                        colorHex = colorHex,
                        altitude = altitude,
                        altitudeSource = altitudeSource
                    )
                )
            }
        }
        return list
    }

    // ── Local Elevation Cache ──────────────────────────────────────────────────
    fun getCachedElevation(lat: Double, lng: Double): Double? {
        val gridKey = "%.4f,%.4f".format(java.util.Locale.US, lat, lng)
        val db = readableDatabase
        return try {
            val cursor = db.query(
                TABLE_ELEVATION_CACHE,
                arrayOf(COL_EC_ELEVATION),
                "$COL_EC_GRID_KEY = ?",
                arrayOf(gridKey),
                null, null, null
            )
            cursor.use {
                if (it.moveToFirst()) it.getDouble(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun putCachedElevations(entries: List<Triple<Double, Double, Double>>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for ((lat, lng, elev) in entries) {
                val gridKey = "%.4f,%.4f".format(java.util.Locale.US, lat, lng)
                val cv = ContentValues().apply {
                    put(COL_EC_GRID_KEY, gridKey)
                    put(COL_EC_ELEVATION, elev)
                    put(COL_EC_UPDATED_AT, now)
                }
                db.insertWithOnConflict(TABLE_ELEVATION_CACHE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }



    fun splitTripAtPause(tripId: Long, pauseIndex: Int): Pair<Long, Long>? {
        val trip = getTripsByIds(listOf(tripId)).firstOrNull() ?: return null
        if (pauseIndex !in trip.pauses.indices) return null
        val pause = trip.pauses[pauseIndex]
        val splitIdx = pause.pointIndex.coerceIn(1, (trip.points.size - 1).coerceAtLeast(1))

        val allRows = getTrackPointRows(tripId)
        val pointsPart1 = trip.points.take(splitIdx)
        val pointsPart2 = trip.points.drop(splitIdx)

        if (pointsPart1.isEmpty() || pointsPart2.isEmpty()) return null

        val pausesPart1 = trip.pauses.take(pauseIndex)
        val pausesPart2 = trip.pauses.drop(pauseIndex + 1).map {
            it.copy(pointIndex = (it.pointIndex - splitIdx).coerceAtLeast(0))
        }

        val splitTime = pause.startTime
        val resumeTime = pause.endTime ?: (pause.startTime + pause.durationMs)

        val placesPart1 = trip.placesVisited.filter { it.timestamp <= splitTime }
        val placesPart2 = trip.placesVisited.filter { it.timestamp > splitTime }

        // Recalculate distance for Part 1
        var dist1 = 0.0
        for (i in 0 until pointsPart1.size - 1) {
            val p1 = pointsPart1[i]
            val p2 = pointsPart1[i + 1]
            val res = FloatArray(1)
            android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
            dist1 += res[0]
        }

        // Recalculate distance for Part 2
        var dist2 = 0.0
        for (i in 0 until pointsPart2.size - 1) {
            val p1 = pointsPart2[i]
            val p2 = pointsPart2[i + 1]
            val res = FloatArray(1)
            android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
            dist2 += res[0]
        }

        val baseTitle = if (trip.title.isNotBlank()) {
            trip.title
        } else {
            val firstCity = trip.placesVisited.firstOrNull()?.placeName ?: "Trip"
            val lastCity = trip.placesVisited.lastOrNull()?.placeName
            if (lastCity != null && lastCity != firstCity) "$firstCity -> $lastCity" else firstCity
        }
        val cleanedTitle = cleanPartSuffix(baseTitle)

        val trip1 = trip.copy(
            title = "$cleanedTitle - Part 1",
            endTime = splitTime,
            distanceMeters = dist1,
            points = pointsPart1,
            placesVisited = placesPart1,
            pauses = pausesPart1
        )
        updateTripMeta(trip1)
        replaceTrackPoints(trip.id, allRows.take(splitIdx))
        invalidateMatch(trip.id)

        val trip2 = TripRecord(
            title = "$cleanedTitle - Part 2",
            startTime = resumeTime,
            endTime = trip.endTime,
            distanceMeters = dist2,
            maxSpeedKmh = trip.maxSpeedKmh,
            avgSpeedKmh = trip.avgSpeedKmh,
            isAutoDetected = trip.isAutoDetected,
            activityProfile = trip.activityProfile,
            points = pointsPart2,
            placesVisited = placesPart2,
            pauses = pausesPart2
        )
        val trip2Id = insertTrip(trip2, rows = allRows.drop(splitIdx))

        return Pair(trip.id, trip2Id)
    }

    fun getTrip(tripId: Long): TripRecord? = getTripsByIds(listOf(tripId)).firstOrNull()

    fun tripHasGpsAltitudePoints(tripId: Long): Boolean =
        getTrackPointRows(tripId).any { (it.altSource ?: "GPS") == "GPS" }

    fun deleteTripPause(tripId: Long, pauseIndex: Int): Boolean {
        val trip = getTrip(tripId) ?: return false
        if (pauseIndex < 0 || pauseIndex >= trip.pauses.size) return false
        val removedPause = trip.pauses[pauseIndex]
        val updatedPauses = trip.pauses.toMutableList()
        updatedPauses.removeAt(pauseIndex)

        var newStartTime = trip.startTime
        var newEndTime = trip.endTime

        // If deleting the ending pause, trim trip endTime back to pause.startTime or the last point before the pause
        if (pauseIndex == trip.pauses.size - 1) {
            val lastPointTime = if (removedPause.pointIndex in trip.points.indices) {
                // If pointIndex is valid, use the timestamp if known or pause.startTime
                removedPause.startTime
            } else {
                removedPause.startTime
            }
            newEndTime = lastPointTime
        }

        // If deleting the starting pause, advance trip startTime to pause.endTime
        if (pauseIndex == 0 && (removedPause.pointIndex <= 1 || removedPause.startTime <= trip.startTime + 15_000L)) {
            val pauseEnd = removedPause.endTime ?: (removedPause.startTime + removedPause.durationMs)
            newStartTime = pauseEnd
        }

        // Recalculate moving time and average speed
        val totalDurationMs = ((newEndTime ?: trip.startTime) - newStartTime).coerceAtLeast(0L)
        val pauseDurationMs = updatedPauses.sumOf { it.durationMs }
        val movingDurationHours = (totalDurationMs - pauseDurationMs).coerceAtLeast(1_000L) / 3600000.0
        val newAvgSpeed = if (movingDurationHours > 0.0 && trip.distanceMeters > 0.0) {
            ((trip.distanceMeters / 1000.0) / movingDurationHours).toFloat().coerceIn(0f, 250f)
        } else {
            trip.avgSpeedKmh
        }

        val updatedTrip = trip.copy(
            startTime = newStartTime,
            endTime = newEndTime,
            avgSpeedKmh = newAvgSpeed,
            pauses = updatedPauses
        )
        updateTripMeta(updatedTrip)
        return true
    }

    fun mergeTripPauses(tripId: Long, pauseIndex: Int): Boolean {
        val trip = getTrip(tripId) ?: return false
        if (pauseIndex < 0 || pauseIndex >= trip.pauses.size - 1) return false
        val p1 = trip.pauses[pauseIndex]
        val p2 = trip.pauses[pauseIndex + 1]

        val mergedEndTime = p2.endTime ?: (p2.startTime + p2.durationMs)
        val mergedDuration = (mergedEndTime - p1.startTime).coerceAtLeast(p1.durationMs + p2.durationMs)
        val mergedPause = TripPause(
            startTime = p1.startTime,
            endTime = mergedEndTime,
            latitude = (p1.latitude + p2.latitude) / 2.0,
            longitude = (p1.longitude + p2.longitude) / 2.0,
            durationMs = mergedDuration,
            pointIndex = p1.pointIndex,
            distanceMeters = if (p1.distanceMeters > 0.0) p1.distanceMeters else p2.distanceMeters
        )

        val updatedPauses = trip.pauses.toMutableList()
        updatedPauses[pauseIndex] = mergedPause
        updatedPauses.removeAt(pauseIndex + 1)

        val totalDurationMs = ((trip.endTime ?: trip.startTime) - trip.startTime).coerceAtLeast(0L)
        val pauseDurationMs = updatedPauses.sumOf { it.durationMs }
        val movingDurationHours = (totalDurationMs - pauseDurationMs).coerceAtLeast(1_000L) / 3600000.0
        val newAvgSpeed = if (movingDurationHours > 0.0 && trip.distanceMeters > 0.0) {
            ((trip.distanceMeters / 1000.0) / movingDurationHours).toFloat().coerceIn(0f, 250f)
        } else {
            trip.avgSpeedKmh
        }

        val updatedTrip = trip.copy(
            avgSpeedKmh = newAvgSpeed,
            pauses = updatedPauses
        )
        updateTripMeta(updatedTrip)
        return true
    }

    fun cleanTripPlaces(tripId: Long, cleanedPlaces: List<VisitedPlace>): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PLACES_JSON, placesToJson(cleanedPlaces))
        }
        return db.update(TABLE_TRIPS, values, "$COL_ID = ?", arrayOf(tripId.toString())) > 0
    }
}

