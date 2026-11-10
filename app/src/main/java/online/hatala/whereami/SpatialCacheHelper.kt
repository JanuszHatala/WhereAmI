package online.hatala.whereami

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Persistent SQLite spatial cache for resolved localities, streets, and administrative hierarchies.
 * Resolves coordinates into an ~100m spatial grid cell and caches the multi-language place indefinitely,
 * enabling instant offline awareness and zero network calls when revisiting previous locations.
 */
class SpatialCacheHelper private constructor(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "where_am_i_spatial_cache.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_CACHE = "spatial_cache"
        private const val TABLE_OUTDOOR_CACHE = "outdoor_context_cache"
        private const val COL_GRID_KEY = "grid_key"
        private const val COL_LAT = "latitude"
        private const val COL_LNG = "longitude"
        private const val COL_CITY = "city"
        private const val COL_STREET = "street"
        private const val COL_ROAD_REF = "road_ref"
        private const val COL_HIGHWAY_TYPE = "highway_type"
        private const val COL_EN_JSON = "en_json"
        private const val COL_PL_JSON = "pl_json"
        private const val COL_NATIVE_JSON = "native_json"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_CONTEXT_JSON = "context_json"

        @Volatile
        private var instance: SpatialCacheHelper? = null

        fun getInstance(context: Context): SpatialCacheHelper {
            return instance ?: synchronized(this) {
                instance ?: SpatialCacheHelper(context.applicationContext).also { instance = it }
            }
        }

        fun toGridKey(lat: Double, lng: Double): String {
            return "${String.format(Locale.ROOT, "%.4f", lat)}_${String.format(Locale.ROOT, "%.4f", lng)}"
        }

        fun toLegacyGridKey(lat: Double, lng: Double): String {
            return "${String.format(Locale.ROOT, "%.3f", lat)}_${String.format(Locale.ROOT, "%.3f", lng)}"
        }

        internal fun serializePlaceInfo(place: PlaceInfo): String {
            return JSONObject().apply {
                put("city", place.city)
                put("street", place.street)
                put("roadRef", place.roadRef)
                put("gmina", place.gmina)
                put("powiat", place.powiat)
                put("voivodeship", place.voivodeship)
                put("country", place.country)
                put("countryCode", place.countryCode)
                put("highwayType", place.highwayType)
            }.toString()
        }

        internal fun deserializePlaceInfo(jsonStr: String?): PlaceInfo? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(jsonStr)
                val rawCountry = obj.optString("country", "Unknown Country")
                val cc = obj.optString("countryCode", "")
                val resolvedCountry = if (cc.uppercase() == "PL" || rawCountry.equals("Poland", ignoreCase = true)) "Polska" else rawCountry
                val rawVoivodeship = obj.optString("voivodeship", "Unknown Region")
                val resolvedVoivodeship = if (resolvedCountry == "Polska" || cc.uppercase() == "PL") {
                    LocationManager.canonicalizePolishVoivodeship(rawVoivodeship)
                } else rawVoivodeship
                var rawCity = obj.optString("city", "Unknown City")
                Regex("""^(\p{L}[\p{L}\s.-]+?)(?:-\d+|\s+\d+|\d{2,})$""").matchEntire(rawCity)?.let { m ->
                    val stripped = m.groupValues[1].trim()
                    if (stripped.length >= 2) rawCity = stripped
                }
                val isPoland = cc.uppercase() == "PL" || resolvedCountry == "Polska"
                val rawGmina = obj.optString("gmina").takeIf { it.isNotBlank() }
                val resolvedGmina = if (isPoland && rawGmina != null) {
                    if (!rawGmina.startsWith("gmina", ignoreCase = true) && !rawGmina.startsWith("gm.", ignoreCase = true)) {
                        null
                    } else rawGmina
                } else rawGmina
                val resolvedCity = rawCity
                val rawStreet = obj.optString("street").takeIf { it.isNotBlank() }
                val rawRoadRef = obj.optString("roadRef").takeIf { it.isNotBlank() }
                var cleanStreet = RoadNameNormalizer.sanitizeStreetForCountry(rawStreet, cc) ?: rawStreet
                var cleanRoadRef = RoadNameNormalizer.sanitizeStreetForCountry(rawRoadRef, cc) ?: rawRoadRef
                val hwyType = obj.optString("highwayType").takeIf { it.isNotBlank() }

                // Universal data-driven invariant: Residential / service / track roads or streets with house numbers
                // must NEVER carry a highway code.
                val isResidential = RoadNameNormalizer.isResidentialHighwayType(hwyType)
                val hasHouseNumber = cleanStreet?.let { RoadNameNormalizer.extractHouseNumber(it) != null } == true
                if (isResidential || hasHouseNumber) {
                    cleanStreet = cleanStreet?.let { RoadNameNormalizer.stripParenHighwayRef(it)?.trim() } ?: cleanStreet
                    cleanRoadRef = null
                }

                PlaceInfo(
                    city = resolvedCity,
                    street = cleanStreet,
                    roadRef = cleanRoadRef,
                    gmina = resolvedGmina,
                    powiat = obj.optString("powiat").takeIf { it.isNotBlank() },
                    voivodeship = resolvedVoivodeship,
                    country = resolvedCountry,
                    countryCode = cc,
                    highwayType = obj.optString("highwayType").takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_CACHE (
                $COL_GRID_KEY TEXT PRIMARY KEY,
                $COL_LAT REAL NOT NULL,
                $COL_LNG REAL NOT NULL,
                $COL_CITY TEXT,
                $COL_STREET TEXT,
                $COL_ROAD_REF TEXT,
                $COL_HIGHWAY_TYPE TEXT,
                $COL_EN_JSON TEXT,
                $COL_PL_JSON TEXT,
                $COL_NATIVE_JSON TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_spatial_coords ON $TABLE_CACHE ($COL_LAT, $COL_LNG)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_OUTDOOR_CACHE (
                $COL_GRID_KEY TEXT PRIMARY KEY,
                $COL_LAT REAL NOT NULL,
                $COL_LNG REAL NOT NULL,
                $COL_CONTEXT_JSON TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outdoor_coords ON $TABLE_OUTDOOR_CACHE ($COL_LAT, $COL_LNG)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version, no upgrades yet
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureHighwayTypeColumn(db)
        ensureOutdoorTable(db)
        migrateLegacyKeys(db)
        sanitizeUniversalInvariants(db)
    }

    private fun ensureHighwayTypeColumn(db: SQLiteDatabase) {
        val hasCol = try {
            db.rawQuery("PRAGMA table_info($TABLE_CACHE)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                var found = false
                if (nameIdx >= 0) {
                    while (c.moveToNext()) {
                        if (c.getString(nameIdx) == COL_HIGHWAY_TYPE) {
                            found = true
                            break
                        }
                    }
                }
                found
            }
        } catch (_: Exception) { false }
        if (!hasCol) {
            try {
                db.execSQL("ALTER TABLE $TABLE_CACHE ADD COLUMN $COL_HIGHWAY_TYPE TEXT")
            } catch (_: Exception) {}
        }
    }

    /**
     * Enforces universal architectural data invariants across the spatial cache:
     * 1. Sanitizes duplicate parenthesized highway refs.
     * 2. Sanitizes non-local highway prefixes outside their country of origin.
     * 3. Enforces that residential / non-major ways and house-numbered street addresses
     *    never carry arterial highway codes or primary/trunk road_refs.
     */
    fun sanitizeUniversalInvariants(db: SQLiteDatabase = writableDatabase) {
        try {
            sanitizePolishHighwayCodesOutsidePoland(db)
            sanitizeDupeParenStreets(db)

            // Universal Invariant: Residential / non-major ways or house-numbered addresses
            // must NEVER carry parenthesized arterial highway codes or primary/trunk road_ref
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_ROAD_REF, $COL_HIGHWAY_TYPE, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE ($COL_HIGHWAY_TYPE IN ('residential', 'living_street', 'service', 'track', 'path', 'pedestrian', 'footway', 'unclassified', 'steps')
                       OR $COL_STREET GLOB '*[0-9]*')
                  AND ($COL_ROAD_REF IS NOT NULL 
                       OR $COL_STREET LIKE '%(DK%' OR $COL_STREET LIKE '%(DW%' OR $COL_STREET LIKE '%(A%' OR $COL_STREET LIKE '%(S%'
                       OR $COL_STREET LIKE '%(I/%' OR $COL_STREET LIKE '%(II/%')
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    val street = c.getString(1)
                    val roadRef = c.getString(2)
                    val en = deserializePlaceInfo(c.getString(4))
                    val pl = deserializePlaceInfo(c.getString(5))
                    val native = deserializePlaceInfo(c.getString(6))

                    val cleanStreet = RoadNameNormalizer.stripParenHighwayRef(street)?.trim() ?: street
                    if (cleanStreet == street && roadRef == null) continue

                    val cleanEn = en?.copy(
                        street = RoadNameNormalizer.stripParenHighwayRef(en.street)?.trim() ?: en.street,
                        roadRef = null
                    )
                    val cleanPl = pl?.copy(
                        street = RoadNameNormalizer.stripParenHighwayRef(pl.street)?.trim() ?: pl.street,
                        roadRef = null
                    )
                    val cleanNative = native?.copy(
                        street = RoadNameNormalizer.stripParenHighwayRef(native.street)?.trim() ?: native.street,
                        roadRef = null
                    )

                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_STREET, cleanStreet)
                        putNull(COL_ROAD_REF)
                        if (cleanEn != null) put(COL_EN_JSON, serializePlaceInfo(cleanEn))
                        if (cleanPl != null) put(COL_PL_JSON, serializePlaceInfo(cleanPl))
                        if (cleanNative != null) put(COL_NATIVE_JSON, serializePlaceInfo(cleanNative))
                    })
                }
            }
            if (updates.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for (cv in updates) {
                        val key = cv.getAsString(COL_GRID_KEY)
                        cv.remove(COL_GRID_KEY)
                        db.update(TABLE_CACHE, cv, "$COL_GRID_KEY = ?", arrayOf(key))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                TelemetryLogger.log("SPATIAL_CACHE", "Universal invariant sanitizer cleaned ${updates.size} residential rows with false highway refs")
            }
        } catch (e: Exception) {
            TelemetryLogger.log("SPATIAL_CACHE", "sanitizeUniversalInvariants error: ${e.message}")
        }
    }

    fun sanitizeDupeParenStreets(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_ROAD_REF, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_STREET LIKE '%(%) (%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val street = it.getString(1)
                    val roadRef = it.getString(2)
                    val fixed = RoadNameNormalizer.dedupeParenHighwayRef(street) ?: continue
                    if (fixed == street) continue
                    val ref = roadRef ?: RoadNameNormalizer.extractHighwayCode(fixed)
                    val en = deserializePlaceInfo(it.getString(3))
                    val pl = deserializePlaceInfo(it.getString(4))
                    val native = deserializePlaceInfo(it.getString(5))
                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_STREET, fixed)
                        put(COL_ROAD_REF, ref)
                        if (en != null) {
                            put(
                                COL_EN_JSON,
                                serializePlaceInfo(
                                    en.copy(
                                        street = RoadNameNormalizer.dedupeParenHighwayRef(en.street) ?: fixed,
                                        roadRef = en.roadRef ?: ref
                                    )
                                )
                            )
                        }
                        if (pl != null) {
                            put(
                                COL_PL_JSON,
                                serializePlaceInfo(
                                    pl.copy(
                                        street = RoadNameNormalizer.dedupeParenHighwayRef(pl.street) ?: fixed,
                                        roadRef = pl.roadRef ?: ref
                                    )
                                )
                            )
                        }
                        if (native != null) {
                            put(
                                COL_NATIVE_JSON,
                                serializePlaceInfo(
                                    native.copy(
                                        street = RoadNameNormalizer.dedupeParenHighwayRef(native.street) ?: fixed,
                                        roadRef = native.roadRef ?: ref
                                    )
                                )
                            )
                        }
                    })
                }
            }
            if (updates.isEmpty()) return
            db.beginTransaction()
            try {
                for (cv in updates) {
                    val key = cv.getAsString(COL_GRID_KEY)
                    cv.remove(COL_GRID_KEY)
                    db.update(TABLE_CACHE, cv, "$COL_GRID_KEY = ?", arrayOf(key))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {}
    }

    fun sanitizePolishHighwayCodesOutsidePoland(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_ROAD_REF, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_STREET LIKE 'DK%' OR $COL_STREET LIKE 'DW%'
                   OR $COL_STREET LIKE '%(DK%' OR $COL_STREET LIKE '%(DW%'
                   OR $COL_ROAD_REF LIKE 'DK%' OR $COL_ROAD_REF LIKE 'DW%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val street = it.getString(1)
                    val roadRef = it.getString(2)
                    val en = deserializePlaceInfo(it.getString(3))
                    val pl = deserializePlaceInfo(it.getString(4))
                    val native = deserializePlaceInfo(it.getString(5))
                    val cc = pl?.countryCode?.takeIf { c -> c.isNotBlank() }
                        ?: en?.countryCode?.takeIf { c -> c.isNotBlank() }
                        ?: native?.countryCode
                    if (cc.isNullOrBlank() || cc.equals("PL", ignoreCase = true)) continue

                    val newStreet = RoadNameNormalizer.sanitizeStreetForCountry(street, cc)
                    val newRef = RoadNameNormalizer.sanitizeStreetForCountry(roadRef, cc)
                    val newEn = en?.copy(
                        street = RoadNameNormalizer.sanitizeStreetForCountry(en.street, cc) ?: en.street,
                        roadRef = RoadNameNormalizer.sanitizeStreetForCountry(en.roadRef, cc) ?: en.roadRef
                    )
                    val newPl = pl?.copy(
                        street = RoadNameNormalizer.sanitizeStreetForCountry(pl.street, cc) ?: pl.street,
                        roadRef = RoadNameNormalizer.sanitizeStreetForCountry(pl.roadRef, cc) ?: pl.roadRef
                    )
                    val newNative = native?.copy(
                        street = RoadNameNormalizer.sanitizeStreetForCountry(native.street, cc) ?: native.street,
                        roadRef = RoadNameNormalizer.sanitizeStreetForCountry(native.roadRef, cc) ?: native.roadRef
                    )
                    if (newStreet == street && newRef == roadRef &&
                        newEn?.street == en?.street && newPl?.street == pl?.street
                    ) continue
                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_STREET, newStreet)
                        put(COL_ROAD_REF, newRef)
                        if (newEn != null) put(COL_EN_JSON, serializePlaceInfo(newEn))
                        if (newPl != null) put(COL_PL_JSON, serializePlaceInfo(newPl))
                        if (newNative != null) put(COL_NATIVE_JSON, serializePlaceInfo(newNative))
                    })
                }
            }
            if (updates.isEmpty()) return
            db.beginTransaction()
            try {
                for (cv in updates) {
                    val key = cv.getAsString(COL_GRID_KEY)
                    cv.remove(COL_GRID_KEY)
                    db.update(TABLE_CACHE, cv, "$COL_GRID_KEY = ?", arrayOf(key))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {}
    }

    fun findNearestVerifiedLocality(lat: Double, lng: Double, radiusM: Double = 2500.0, gmina: String? = null): String? {
        val deg = radiusM / 111000.0
        val db = readableDatabase
        val query = if (!gmina.isNullOrBlank()) {
            "SELECT $COL_CITY, $COL_LAT, $COL_LNG FROM $TABLE_CACHE WHERE $COL_HIGHWAY_TYPE IS NOT NULL AND $COL_CITY IS NOT NULL AND $COL_CITY NOT LIKE '%.%' AND $COL_LAT BETWEEN ? AND ? AND $COL_LNG BETWEEN ? AND ? AND $COL_PL_JSON LIKE ? LIMIT 20"
        } else {
            "SELECT $COL_CITY, $COL_LAT, $COL_LNG FROM $TABLE_CACHE WHERE $COL_HIGHWAY_TYPE IS NOT NULL AND $COL_CITY IS NOT NULL AND $COL_CITY NOT LIKE '%.%' AND $COL_LAT BETWEEN ? AND ? AND $COL_LNG BETWEEN ? AND ? LIMIT 20"
        }
        val args = if (!gmina.isNullOrBlank()) {
            arrayOf((lat - deg).toString(), (lat + deg).toString(), (lng - deg).toString(), (lng + deg).toString(), "%$gmina%")
        } else {
            arrayOf((lat - deg).toString(), (lat + deg).toString(), (lng - deg).toString(), (lng + deg).toString())
        }
        var closestCity: String? = null
        var closestDist = Float.MAX_VALUE
        try {
            val cursor = db.rawQuery(query, args)
            cursor.use {
                val dist = FloatArray(1)
                while (it.moveToNext()) {
                    val city = it.getString(0) ?: continue
                    val rowLat = it.getDouble(1)
                    val rowLng = it.getDouble(2)
                    android.location.Location.distanceBetween(lat, lng, rowLat, rowLng, dist)
                    if (dist[0] <= radiusM && dist[0] < closestDist) {
                        closestDist = dist[0]
                        closestCity = city
                    }
                }
            }
        } catch (_: Exception) {}
        return closestCity
    }

    /**
     * Identifies missing points or degraded points (e.g. unverified corridor rows without highway_type
     * or corrupted locality names) along a list of trip track points.
     */
    fun getIncompleteOrDegradedTripPoints(points: List<org.osmdroid.util.GeoPoint>): List<org.osmdroid.util.GeoPoint> {
        if (points.isEmpty()) return emptyList()
        val db = readableDatabase
        val degradedOrMissing = mutableListOf<org.osmdroid.util.GeoPoint>()
        val cachedKeys = getAllCachedGridKeys()
        for (pt in points) {
            val key = toGridKey(pt.latitude, pt.longitude)
            if (key !in cachedKeys && !hasNearbyCache(pt.latitude, pt.longitude, 30.0)) {
                degradedOrMissing.add(pt)
            } else {
                try {
                    val cursor = db.rawQuery(
                        "SELECT $COL_HIGHWAY_TYPE, $COL_CITY, $COL_STREET, $COL_ROAD_REF FROM $TABLE_CACHE WHERE $COL_GRID_KEY = ?",
                        arrayOf(key)
                    )
                    cursor.use {
                        if (it.moveToFirst()) {
                            val hw = it.getString(0)
                            val city = it.getString(1) ?: ""
                            val street = it.getString(2)
                            val roadRef = it.getString(3)
                            val isCorridor = RoadNameNormalizer.isMajorRoad(street, roadRef)
                            val isDegraded = (isCorridor && hw == null && roadRef == null) ||
                                city.contains(".") || city.matches(Regex(""".*-\d+$"""))
                            if (isDegraded) {
                                degradedOrMissing.add(pt)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return degradedOrMissing
    }

    /**
     * Migrates legacy 3-decimal keys (e.g. "49.822_19.245") to precise 4-decimal keys ("49.8223_19.2451")
     * using the saved exact latitude and longitude coordinates so existing recorded trip cache data
     * is 100% preserved and never re-downloaded.
     */
    fun migrateLegacyKeys(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                "SELECT $COL_GRID_KEY, $COL_LAT, $COL_LNG, $COL_CITY, $COL_STREET, $COL_ROAD_REF, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON, $COL_TIMESTAMP FROM $TABLE_CACHE",
                null
            )
            val legacyRows = mutableListOf<ContentValues>()
            val keysToDelete = mutableListOf<String>()

            cursor.use {
                while (it.moveToNext()) {
                    val oldKey = it.getString(0)
                    val parts = oldKey.split("_")
                    val is3Decimal = parts.size == 2 && parts.any { p -> p.substringAfter(".", "").length <= 3 }
                    if (is3Decimal) {
                        val lat = it.getDouble(1)
                        val lng = it.getDouble(2)
                        val newKey = toGridKey(lat, lng)
                        val cv = ContentValues().apply {
                            put(COL_GRID_KEY, newKey)
                            put(COL_LAT, lat)
                            put(COL_LNG, lng)
                            put(COL_CITY, it.getString(3))
                            put(COL_STREET, it.getString(4))
                            put(COL_ROAD_REF, it.getString(5))
                            put(COL_EN_JSON, it.getString(6))
                            put(COL_PL_JSON, it.getString(7))
                            put(COL_NATIVE_JSON, it.getString(8))
                            put(COL_TIMESTAMP, it.getLong(9))
                        }
                        legacyRows.add(cv)
                        keysToDelete.add(oldKey)
                    }
                }
            }

            if (legacyRows.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for (cv in legacyRows) {
                        db.insertWithOnConflict(TABLE_CACHE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    for (oldKey in keysToDelete) {
                        db.delete(TABLE_CACHE, "$COL_GRID_KEY = ?", arrayOf(oldKey))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            // One-time canonicalization: migrate any stored "country":"Poland" to "country":"Polska" in JSON columns
            try {
                db.execSQL(
                    "UPDATE $TABLE_CACHE SET " +
                    "$COL_EN_JSON = REPLACE($COL_EN_JSON, '\"country\":\"Poland\"', '\"country\":\"Polska\"'), " +
                    "$COL_PL_JSON = REPLACE($COL_PL_JSON, '\"country\":\"Poland\"', '\"country\":\"Polska\"'), " +
                    "$COL_NATIVE_JSON = REPLACE($COL_NATIVE_JSON, '\"country\":\"Poland\"', '\"country\":\"Polska\"') " +
                    "WHERE $COL_EN_JSON LIKE '%\"country\":\"Poland\"%' " +
                    "OR $COL_PL_JSON LIKE '%\"country\":\"Poland\"%' " +
                    "OR $COL_NATIVE_JSON LIKE '%\"country\":\"Poland\"%'"
                )
                db.execSQL(
                    "UPDATE $TABLE_CACHE SET $COL_CITY = 'Kozy', " +
                    "$COL_EN_JSON = REPLACE($COL_EN_JSON, '\"city\":\"Krzemionki\"', '\"city\":\"Kozy\"'), " +
                    "$COL_PL_JSON = REPLACE($COL_PL_JSON, '\"city\":\"Krzemionki\"', '\"city\":\"Kozy\"'), " +
                    "$COL_NATIVE_JSON = REPLACE($COL_NATIVE_JSON, '\"city\":\"Krzemionki\"', '\"city\":\"Kozy\"') " +
                    "WHERE ($COL_CITY = 'Krzemionki' OR $COL_PL_JSON LIKE '%\"city\":\"Krzemionki\"%') " +
                    "AND ($COL_PL_JSON LIKE '%gmina Kozy%' OR $COL_PL_JSON LIKE '%gm. Kozy%')"
                )
                db.execSQL(
                    "UPDATE $TABLE_CACHE SET $COL_CITY = 'Kozy', " +
                    "$COL_EN_JSON = REPLACE($COL_EN_JSON, '\"city\":\"Małe Kozy\"', '\"city\":\"Kozy\"'), " +
                    "$COL_PL_JSON = REPLACE($COL_PL_JSON, '\"city\":\"Małe Kozy\"', '\"city\":\"Kozy\"'), " +
                    "$COL_NATIVE_JSON = REPLACE($COL_NATIVE_JSON, '\"city\":\"Małe Kozy\"', '\"city\":\"Kozy\"') " +
                    "WHERE ($COL_CITY = 'Małe Kozy' OR $COL_PL_JSON LIKE '%\"city\":\"Małe Kozy\"%') " +
                    "AND ($COL_PL_JSON LIKE '%gmina Kozy%' OR $COL_PL_JSON LIKE '%gm. Kozy%')"
                )
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    data class TimedPlaceInfo(
        val info: MultiLanguagePlaceInfo,
        val timestamp: Long
    )

    fun getWithTimestamp(lat: Double, lng: Double): TimedPlaceInfo? {
        val key = toGridKey(lat, lng)
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CACHE,
            arrayOf(COL_EN_JSON, COL_PL_JSON, COL_NATIVE_JSON, COL_TIMESTAMP),
            "$COL_GRID_KEY = ?",
            arrayOf(key),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val timestamp = it.getLong(3)
                val enJson = it.getString(0)
                val plJson = it.getString(1)
                val nativeJson = it.getString(2)
                val en = deserializePlaceInfo(enJson)
                val pl = deserializePlaceInfo(plJson)
                val native = deserializePlaceInfo(nativeJson)
                if (en != null && pl != null && native != null) {
                    return TimedPlaceInfo(MultiLanguagePlaceInfo(en = en, pl = pl, native = native), timestamp)
                }
            }
        }

        // Fallback: Check legacy 3-decimal key if 4-decimal key not yet created
        val legacyKey = toLegacyGridKey(lat, lng)
        val cursorLegacy = db.query(
            TABLE_CACHE,
            arrayOf(COL_EN_JSON, COL_PL_JSON, COL_NATIVE_JSON, COL_TIMESTAMP),
            "$COL_GRID_KEY = ?",
            arrayOf(legacyKey),
            null,
            null,
            null
        )
        cursorLegacy.use {
            if (it.moveToFirst()) {
                val timestamp = it.getLong(3)
                val enJson = it.getString(0)
                val plJson = it.getString(1)
                val nativeJson = it.getString(2)
                val en = deserializePlaceInfo(enJson)
                val pl = deserializePlaceInfo(plJson)
                val native = deserializePlaceInfo(nativeJson)
                if (en != null && pl != null && native != null) {
                    return TimedPlaceInfo(MultiLanguagePlaceInfo(en = en, pl = pl, native = native), timestamp)
                }
            }
        }
        return null
    }

    fun get(lat: Double, lng: Double, maxAgeMs: Long? = null): MultiLanguagePlaceInfo? {
        val timed = getWithTimestamp(lat, lng) ?: return null
        if (maxAgeMs != null) {
            if (System.currentTimeMillis() - timed.timestamp > maxAgeMs) {
                return null
            }
        }
        return timed.info
    }

    fun enforceStorageLimit(maxSizeMb: Int) {
        val maxBytes = maxSizeMb.toLong() * 1024L * 1024L
        val currentBytes = getStorageBytes()
        if (currentBytes <= maxBytes) return
        val db = writableDatabase
        db.execSQL("DELETE FROM $TABLE_CACHE WHERE $COL_CITY IS NULL OR $COL_CITY IN ('Unknown City', '--', 'Likwidacja', 'Serwis', 'Powiat', 'koło')")
        val oneYearAgo = System.currentTimeMillis() - (365L * 24 * 3600 * 1000L)
        db.execSQL("DELETE FROM $TABLE_CACHE WHERE $COL_TIMESTAMP < $oneYearAgo")
        if (getStorageBytes() > maxBytes) {
            db.execSQL("""
                DELETE FROM $TABLE_CACHE 
                WHERE $COL_GRID_KEY IN (
                    SELECT $COL_GRID_KEY FROM $TABLE_CACHE 
                    ORDER BY $COL_TIMESTAMP ASC 
                    LIMIT 10000
                )
            """.trimIndent())
        }
    }

    fun isCached(lat: Double, lng: Double): Boolean {
        return get(lat, lng, maxAgeMs = null) != null
    }

    /**
     * Bulk-loads all valid cached grid keys into a HashSet.
     * Replaces thousands of individual point-by-point SQLite lookups with a single ~10ms scan.
     */
    fun getAllCachedGridKeys(): Set<String> {
        val keys = HashSet<String>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT $COL_GRID_KEY FROM $TABLE_CACHE WHERE $COL_EN_JSON IS NOT NULL AND $COL_PL_JSON IS NOT NULL",
                null
            )
            cursor.use {
                val keyIdx = it.getColumnIndex(COL_GRID_KEY)
                if (keyIdx >= 0) {
                    while (it.moveToNext()) {
                        val k = it.getString(keyIdx)
                        if (!k.isNullOrEmpty()) {
                            keys.add(k)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return keys
    }

    /**
     * Checks if any cached address exists within [radiusMeters] (default 50m) of this point.
     * Prevents redundant corridor network queries where spatial awareness data is already available.
     */
    fun hasNearbyCache(lat: Double, lng: Double, radiusMeters: Double = 50.0): Boolean {
        if (isCached(lat, lng)) return true
        val deltaLat = radiusMeters / 111320.0
        val deltaLng = radiusMeters / (111320.0 * Math.cos(Math.toRadians(lat)))
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM $TABLE_CACHE WHERE $COL_LAT BETWEEN ? AND ? AND $COL_LNG BETWEEN ? AND ? LIMIT 1",
            arrayOf(
                (lat - deltaLat).toString(),
                (lat + deltaLat).toString(),
                (lng - deltaLng).toString(),
                (lng + deltaLng).toString()
            )
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    /**
     * Inspects nearby spatial cache entries within [radiusMeters] for a known house number
     * on the same street name. Used when stopped or at low speed to enrich street cards.
     */
    fun findNearbyHouseNumber(lat: Double, lng: Double, streetName: String?, radiusMeters: Double = 35.0): String? {
        if (streetName.isNullOrBlank()) return null
        val deltaLat = radiusMeters / 111320.0
        val deltaLng = radiusMeters / (111320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT $COL_STREET FROM $TABLE_CACHE WHERE $COL_LAT BETWEEN ? AND ? AND $COL_LNG BETWEEN ? AND ?",
            arrayOf(
                (lat - deltaLat).toString(),
                (lat + deltaLat).toString(),
                (lng - deltaLng).toString(),
                (lng + deltaLng).toString()
            )
        )
        cursor.use {
            while (it.moveToNext()) {
                val candidateStreet = it.getString(0)
                if (RoadNameNormalizer.streetsMatch(streetName, candidateStreet)) {
                    val num = RoadNameNormalizer.extractHouseNumber(candidateStreet)
                    if (!num.isNullOrBlank()) return num
                }
            }
        }
        return null
    }

    fun put(lat: Double, lng: Double, info: MultiLanguagePlaceInfo) {
        val key = toGridKey(lat, lng)
        // Never let a bare DW/DK/A/S code overwrite a local street name already cached in this cell
        // (Kobiernice Krakowska vs DW948 on the same ~11 m grid). Named incoming always wins over bare existing.
        val existing = get(lat, lng, maxAgeMs = null)
        val incomingNamedWins = existing != null &&
            RoadNameNormalizer.shouldPreferExistingStreet(info.pl.street, existing.pl.street)

        val existingIsMajor = existing != null && RoadNameNormalizer.isMajorRoad(existing.pl.street, existing.pl.roadRef)
        val incomingBase = RoadNameNormalizer.extractBaseStreet(info.pl.street)
        val existingBase = if (existing != null) RoadNameNormalizer.extractBaseStreet(existing.pl.street) else ""
        val sameBaseCorridor = existingIsMajor && incomingBase.isNotEmpty() && incomingBase.equals(existingBase, ignoreCase = true)

        val toStore = if (existing != null && (
            RoadNameNormalizer.shouldPreferExistingStreet(existing.pl.street, info.pl.street) ||
            sameBaseCorridor
        )) {
            val ref = info.pl.roadRef
                ?: RoadNameNormalizer.extractHighwayCode(info.pl.street)
                ?: existing.pl.roadRef
            val displayStreet = if (sameBaseCorridor && ref != null && RoadNameNormalizer.isMajorRoad(existingBase, ref)) {
                RoadNameNormalizer.formatStreetDisplay(existingBase, ref, existing.pl.countryCode) ?: existing.pl.street
            } else {
                existing.pl.street
            }
            existing.copy(
                en = existing.en.copy(
                    roadRef = ref ?: existing.en.roadRef,
                    street = RoadNameNormalizer.dedupeParenHighwayRef(displayStreet) ?: displayStreet
                ),
                pl = existing.pl.copy(
                    roadRef = ref ?: existing.pl.roadRef,
                    street = RoadNameNormalizer.dedupeParenHighwayRef(displayStreet) ?: displayStreet
                ),
                native = existing.native.copy(
                    roadRef = ref ?: existing.native.roadRef,
                    street = RoadNameNormalizer.dedupeParenHighwayRef(displayStreet) ?: displayStreet
                )
            )
        } else if (incomingNamedWins) {
            // Incoming named street replaces bare highway code: never inherit stray highway ref from existing cell
            val ref = info.pl.roadRef
                ?: RoadNameNormalizer.extractHighwayCode(info.pl.street)
            val streetPl = RoadNameNormalizer.dedupeParenHighwayRef(
                RoadNameNormalizer.formatStreetDisplay(info.pl.street, ref, info.pl.countryCode)
            ) ?: info.pl.street
            info.copy(
                en = info.en.copy(street = streetPl, roadRef = ref),
                pl = info.pl.copy(street = streetPl, roadRef = ref),
                native = info.native.copy(street = streetPl, roadRef = ref)
            )
        } else {
            val refPl = info.pl.roadRef ?: RoadNameNormalizer.extractHighwayCode(info.pl.street)
            val streetPl = RoadNameNormalizer.dedupeParenHighwayRef(info.pl.street) ?: info.pl.street
            if (refPl != null && info.pl.roadRef.isNullOrBlank()) {
                info.copy(
                    en = info.en.copy(
                        roadRef = info.en.roadRef ?: RoadNameNormalizer.extractHighwayCode(info.en.street) ?: refPl,
                        street = RoadNameNormalizer.dedupeParenHighwayRef(info.en.street) ?: info.en.street
                    ),
                    pl = info.pl.copy(roadRef = refPl, street = streetPl),
                    native = info.native.copy(
                        roadRef = info.native.roadRef ?: RoadNameNormalizer.extractHighwayCode(info.native.street) ?: refPl,
                        street = RoadNameNormalizer.dedupeParenHighwayRef(info.native.street) ?: info.native.street
                    )
                )
            } else {
                info.copy(
                    en = info.en.copy(street = RoadNameNormalizer.dedupeParenHighwayRef(info.en.street) ?: info.en.street),
                    pl = info.pl.copy(street = streetPl),
                    native = info.native.copy(street = RoadNameNormalizer.dedupeParenHighwayRef(info.native.street) ?: info.native.street)
                )
            }
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_GRID_KEY, key)
            put(COL_LAT, lat)
            put(COL_LNG, lng)
            put(COL_CITY, toStore.pl.city)
            put(COL_STREET, toStore.pl.street)
            put(COL_ROAD_REF, toStore.pl.roadRef)
            put(COL_HIGHWAY_TYPE, toStore.pl.highwayType ?: toStore.en.highwayType)
            put(COL_EN_JSON, serializePlaceInfo(toStore.en))
            put(COL_PL_JSON, serializePlaceInfo(toStore.pl))
            put(COL_NATIVE_JSON, serializePlaceInfo(toStore.native))
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_CACHE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getRecordCount(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CACHE", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    fun getStorageBytes(): Long {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        var total = if (dbFile.exists()) dbFile.length() else 0L
        val walFile = File(dbFile.path + "-wal")
        if (walFile.exists()) total += walFile.length()
        val shmFile = File(dbFile.path + "-shm")
        if (shmFile.exists()) total += shmFile.length()
        return total
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_CACHE, null, null)
        db.delete(TABLE_OUTDOOR_CACHE, null, null)
    }

    private fun ensureOutdoorTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_OUTDOOR_CACHE (
                $COL_GRID_KEY TEXT PRIMARY KEY,
                $COL_LAT REAL NOT NULL,
                $COL_LNG REAL NOT NULL,
                $COL_CONTEXT_JSON TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outdoor_coords ON $TABLE_OUTDOOR_CACHE ($COL_LAT, $COL_LNG)")
    }

    fun getOutdoorContext(lat: Double, lng: Double, maxAgeMs: Long? = 6 * 3600 * 1000L): OutdoorContext? {
        val key = toGridKey(lat, lng)
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT $COL_CONTEXT_JSON, $COL_TIMESTAMP FROM $TABLE_OUTDOOR_CACHE WHERE $COL_GRID_KEY = ?",
            arrayOf(key)
        )
        cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                val timestamp = it.getLong(1)
                if (maxAgeMs != null && (System.currentTimeMillis() - timestamp) > maxAgeMs) {
                    return null
                }
                return deserializeOutdoorContext(json)
            }
        }
        return null
    }

    fun putOutdoorContext(lat: Double, lng: Double, outdoorContext: OutdoorContext) {
        val key = toGridKey(lat, lng)
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_GRID_KEY, key)
            put(COL_LAT, lat)
            put(COL_LNG, lng)
            put(COL_CONTEXT_JSON, serializeOutdoorContext(outdoorContext))
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_OUTDOOR_CACHE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun serializeOutdoorContext(c: OutdoorContext): String {
        val root = JSONObject()
        c.mountainRange?.let {
            root.put("range", JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("nameEn", it.nameEn)
                put("kind", it.kind.name)
                put("osmRelId", it.osmRelationId ?: 0L)
            })
        }
        val protArr = org.json.JSONArray()
        c.protectedAreas.forEach {
            protArr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("nameEn", it.nameEn)
                put("kind", it.kind.name)
                put("osmRelId", it.osmRelationId ?: 0L)
            })
        }
        root.put("protected", protArr)
        c.valley?.let {
            root.put("valley", JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("nameEn", it.nameEn)
                put("kind", it.kind.name)
                put("osmRelId", it.osmRelationId ?: 0L)
            })
        }
        val trailsArr = org.json.JSONArray()
        c.activeTrails.forEach {
            trailsArr.put(JSONObject().apply {
                put("osmId", it.osmId)
                put("name", it.name)
                put("ref", it.ref)
                put("kind", it.kind.name)
                put("color", it.color.name)
                put("network", it.network)
                put("distanceMeters", it.distanceMeters.toDouble())
            })
        }
        root.put("trails", trailsArr)
        val featArr = org.json.JSONArray()
        c.nearestFeatures.forEach {
            featArr.put(JSONObject().apply {
                put("osmId", it.osmId)
                put("name", it.name)
                put("kind", it.kind.name)
                put("ele", it.elevationMeters ?: -1)
                put("dist", it.distanceMeters.toDouble())
                put("lat", it.latitude)
                put("lon", it.longitude)
            })
        }
        root.put("features", featArr)
        c.waterBody?.let {
            root.put("water", JSONObject().apply {
                put("osmId", it.osmId)
                put("name", it.name)
                put("kind", it.kind.name)
                put("ele", it.elevationMeters ?: -1)
                put("dist", it.distanceMeters.toDouble())
                put("lat", it.latitude)
                put("lon", it.longitude)
            })
        }
        root.put("timestamp", c.timestamp)
        return root.toString()
    }

    internal fun deserializeOutdoorContext(jsonStr: String?): OutdoorContext? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val root = JSONObject(jsonStr)
            val rangeObj = root.optJSONObject("range")
            val range = rangeObj?.let {
                OutdoorArea(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    nameEn = it.optString("nameEn").takeIf { s -> s.isNotBlank() },
                    kind = try { OutdoorAreaKind.valueOf(it.optString("kind")) } catch (_: Exception) { OutdoorAreaKind.MOUNTAIN_RANGE },
                    osmRelationId = it.optLong("osmRelId").takeIf { id -> id > 0 }
                )
            }
            val protList = mutableListOf<OutdoorArea>()
            val protArr = root.optJSONArray("protected")
            if (protArr != null) {
                for (i in 0 until protArr.length()) {
                    val p = protArr.optJSONObject(i) ?: continue
                    protList.add(
                        OutdoorArea(
                            id = p.optString("id"),
                            name = p.optString("name"),
                            nameEn = p.optString("nameEn").takeIf { s -> s.isNotBlank() },
                            kind = try { OutdoorAreaKind.valueOf(p.optString("kind")) } catch (_: Exception) { OutdoorAreaKind.LANDSCAPE_PARK },
                            osmRelationId = p.optLong("osmRelId").takeIf { id -> id > 0 }
                        )
                    )
                }
            }
            val valleyObj = root.optJSONObject("valley")
            val valley = valleyObj?.let {
                OutdoorArea(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    nameEn = it.optString("nameEn").takeIf { s -> s.isNotBlank() },
                    kind = OutdoorAreaKind.VALLEY,
                    osmRelationId = it.optLong("osmRelId").takeIf { id -> id > 0 }
                )
            }
            val trails = mutableListOf<ActiveTrailInfo>()
            val trailsArr = root.optJSONArray("trails")
            if (trailsArr != null) {
                for (i in 0 until trailsArr.length()) {
                    val t = trailsArr.optJSONObject(i) ?: continue
                    trails.add(
                        ActiveTrailInfo(
                            osmId = t.optLong("osmId"),
                            name = t.optString("name"),
                            ref = t.optString("ref").takeIf { s -> s.isNotBlank() },
                            kind = try { TrailRouteKind.valueOf(t.optString("kind")) } catch (_: Exception) { TrailRouteKind.HIKING },
                            color = try { TrailColor.valueOf(t.optString("color")) } catch (_: Exception) { TrailColor.NONE },
                            network = t.optString("network").takeIf { s -> s.isNotBlank() },
                            distanceMeters = t.optDouble("distanceMeters", 0.0).toFloat()
                        )
                    )
                }
            }
            val features = mutableListOf<OutdoorFeatureInfo>()
            val featArr = root.optJSONArray("features")
            if (featArr != null) {
                for (i in 0 until featArr.length()) {
                    val f = featArr.optJSONObject(i) ?: continue
                    features.add(
                        OutdoorFeatureInfo(
                            osmId = f.optLong("osmId"),
                            name = f.optString("name"),
                            kind = try { PlaceKind.valueOf(f.optString("kind")) } catch (_: Exception) { PlaceKind.PEAK },
                            elevationMeters = f.optInt("ele").takeIf { e -> e > 0 },
                            distanceMeters = f.optDouble("dist", 0.0).toFloat(),
                            latitude = f.optDouble("lat", 0.0),
                            longitude = f.optDouble("lon", 0.0)
                        )
                    )
                }
            }
            val waterObj = root.optJSONObject("water")
            val water = waterObj?.let {
                OutdoorFeatureInfo(
                    osmId = it.optLong("osmId"),
                    name = it.optString("name"),
                    kind = PlaceKind.WATER_BODY,
                    elevationMeters = it.optInt("ele").takeIf { e -> e > 0 },
                    distanceMeters = it.optDouble("dist", 0.0).toFloat(),
                    latitude = it.optDouble("lat", 0.0),
                    longitude = it.optDouble("lon", 0.0)
                )
            }
            val ts = root.optLong("timestamp", System.currentTimeMillis())
            OutdoorContext(range, protList, valley, trails, features, water, ts)
        } catch (_: Exception) {
            null
        }
    }
}
