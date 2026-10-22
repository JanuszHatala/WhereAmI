package janush.tech.whereami

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
        private const val COL_GRID_KEY = "grid_key"
        private const val COL_LAT = "latitude"
        private const val COL_LNG = "longitude"
        private const val COL_CITY = "city"
        private const val COL_STREET = "street"
        private const val COL_ROAD_REF = "road_ref"
        private const val COL_EN_JSON = "en_json"
        private const val COL_PL_JSON = "pl_json"
        private const val COL_NATIVE_JSON = "native_json"
        private const val COL_TIMESTAMP = "timestamp"

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
                val rawCity = obj.optString("city", "Unknown City")
                val rawGmina = obj.optString("gmina").takeIf { it.isNotBlank() }
                val resolvedCity = if (rawGmina?.contains("gm. Kozy", ignoreCase = true) == true || rawGmina?.contains("gmina Kozy", ignoreCase = true) == true) {
                    if (rawCity.equals("Krzemionki", ignoreCase = true) || rawCity.equals("Małe Kozy", ignoreCase = true)) {
                        "Kozy"
                    } else rawCity
                } else rawCity
                val rawStreet = obj.optString("street").takeIf { it.isNotBlank() }
                val rawRoadRef = obj.optString("roadRef").takeIf { it.isNotBlank() }
                PlaceInfo(
                    city = resolvedCity,
                    street = RoadNameNormalizer.sanitizeStreetForCountry(rawStreet, cc) ?: rawStreet,
                    roadRef = RoadNameNormalizer.sanitizeStreetForCountry(rawRoadRef, cc) ?: rawRoadRef,
                    gmina = rawGmina,
                    powiat = obj.optString("powiat").takeIf { it.isNotBlank() },
                    voivodeship = resolvedVoivodeship,
                    country = resolvedCountry,
                    countryCode = cc
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
                $COL_EN_JSON TEXT,
                $COL_PL_JSON TEXT,
                $COL_NATIVE_JSON TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_spatial_coords ON $TABLE_CACHE ($COL_LAT, $COL_LNG)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version, no upgrades yet
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        migrateLegacyKeys(db)
        sanitizeKnownMismatches(db)
        sanitizePolishHighwayCodesOutsidePoland(db)
        sanitizeBareHighwayAndDupeParenStreets(db)
        sanitizeFalseHighwayOnLocalNames(db)
    }

    /**
     * Strips glued highway annotations from commemorative / multi-token local names
     * (e.g. `Kardynała Karola Wojtyły (DK52)` → `Kardynała Karola Wojtyły`, clear road_ref).
     * Corridor aliases like `Krakowska (DK52)` are kept. Field trip 249 Czaniec 2026-10-22.
     */
    fun sanitizeFalseHighwayOnLocalNames(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_ROAD_REF, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_STREET LIKE '%(DK%' OR $COL_STREET LIKE '%(DW%'
                   OR $COL_STREET LIKE '%(A%' OR $COL_STREET LIKE '%(S%'
                   OR $COL_ROAD_REF LIKE 'DK%' OR $COL_ROAD_REF LIKE 'DW%'
                   OR $COL_ROAD_REF LIKE 'A%' OR $COL_ROAD_REF LIKE 'S%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val street = it.getString(1) ?: continue
                    val roadRef = it.getString(2)
                    val base = RoadNameNormalizer.stripParenHighwayRef(street) ?: street
                    if (RoadNameNormalizer.isLikelyCorridorLocalName(base)) continue
                    val hasParen = street.contains('(')
                    val hasHighwayRef = !roadRef.isNullOrBlank() &&
                        RoadNameNormalizer.isHighwayCodeOnly(roadRef.trim())
                    if (!hasParen && !hasHighwayRef) continue
                    val en = deserializePlaceInfo(it.getString(3))
                    val pl = deserializePlaceInfo(it.getString(4))
                    val native = deserializePlaceInfo(it.getString(5))
                    fun PlaceInfo.cleaned(): PlaceInfo {
                        val s = RoadNameNormalizer.stripParenHighwayRef(this.street) ?: this.street
                        return copy(street = s, roadRef = null)
                    }
                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_STREET, base)
                        putNull(COL_ROAD_REF)
                        if (en != null) put(COL_EN_JSON, serializePlaceInfo(en.cleaned()))
                        if (pl != null) put(COL_PL_JSON, serializePlaceInfo(pl.cleaned()))
                        if (native != null) put(COL_NATIVE_JSON, serializePlaceInfo(native.cleaned()))
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

    /**
     * Removes bare `DK*`/`DW*`/`A*`/`S*` street cells (force re-geocode to local names) and collapses
     * doubled annotations like `Krakowska (DK52) (DK52)`. Field round 2026-10-21: 600 bare DK52 rows
     * and 23 doubled-paren rows kept the locality card on the highway code.
     */
    fun sanitizeBareHighwayAndDupeParenStreets(db: SQLiteDatabase = writableDatabase) {
        try {
            db.execSQL(
                """
                DELETE FROM $TABLE_CACHE
                WHERE $COL_STREET GLOB 'DK[0-9]*'
                   OR $COL_STREET GLOB 'DW[0-9]*'
                   OR $COL_STREET GLOB 'A[0-9]*'
                   OR $COL_STREET GLOB 'S[0-9]*'
                   OR $COL_STREET GLOB 'E[0-9]*'
                """.trimIndent()
            )
        } catch (_: Exception) {}
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

    /**
     * Purges legacy Google Geocoder misidentifications along Witosa & Przecznia
     * so verified OpenStreetMap road vectors are cleanly re-cached indefinitely.
     */
    fun sanitizeKnownMismatches(db: SQLiteDatabase = writableDatabase) {
        try {
            db.execSQL("""
                DELETE FROM $TABLE_CACHE 
                WHERE (street LIKE '%Krzemionki%' AND latitude BETWEEN 49.8415 AND 49.8445)
                   OR (street LIKE '%Klonowa%' AND latitude BETWEEN 49.8510 AND 49.8525 AND longitude BETWEEN 19.1420 AND 19.1435)
                   OR ((latitude BETWEEN 49.8350 AND 49.8550 AND longitude BETWEEN 19.0450 AND 19.0750)
                       AND (street LIKE '%Braterska%' 
                         OR street LIKE '%Chłopickiego%' 
                         OR street LIKE '%Baczyńskiego%' 
                         OR street LIKE '%Kryształowa%' 
                         OR street LIKE '%Czerwona%' 
                         OR street LIKE '%Bukietowa%'))
                   OR city IN ('Likwidacja', 'Serwis', 'Powiat', 'koło')
            """.trimIndent())
        } catch (_: Exception) {}
    }

    /**
     * Rewrites Polish `DK*` / `DW*` street labels stored under non-PL [countryCode] (e.g. Slovakia
     * `DW537` → `II/537`). Idempotent; safe to run on every open.
     */
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

    fun put(lat: Double, lng: Double, info: MultiLanguagePlaceInfo) {
        val key = toGridKey(lat, lng)
        // Never let a bare DW/DK/A/S code overwrite a local street name already cached in this cell
        // (Kobiernice Krakowska vs DW948 on the same ~11 m grid). Named incoming always wins over bare existing.
        val existing = get(lat, lng, maxAgeMs = null)
        val incomingNamedWins = existing != null &&
            RoadNameNormalizer.shouldPreferExistingStreet(info.pl.street, existing.pl.street)
        val toStore = if (existing != null &&
            RoadNameNormalizer.shouldPreferExistingStreet(existing.pl.street, info.pl.street)
        ) {
            val ref = info.pl.roadRef
                ?: RoadNameNormalizer.extractHighwayCode(info.pl.street)
                ?: existing.pl.roadRef
            existing.copy(
                en = existing.en.copy(
                    roadRef = ref ?: existing.en.roadRef,
                    street = RoadNameNormalizer.dedupeParenHighwayRef(existing.en.street) ?: existing.en.street
                ),
                pl = existing.pl.copy(
                    roadRef = ref ?: existing.pl.roadRef,
                    street = RoadNameNormalizer.dedupeParenHighwayRef(existing.pl.street) ?: existing.pl.street
                ),
                native = existing.native.copy(
                    roadRef = ref ?: existing.native.roadRef,
                    street = RoadNameNormalizer.dedupeParenHighwayRef(existing.native.street) ?: existing.native.street
                )
            )
        } else if (incomingNamedWins) {
            val ref = info.pl.roadRef
                ?: RoadNameNormalizer.extractHighwayCode(info.pl.street)
                ?: existing!!.pl.roadRef
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
    }
}
