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
                if (rawCity.startsWith("Międzybrodzie Kob", ignoreCase = true)) {
                    rawCity = "Międzybrodzie Bialskie"
                }
                Regex("""^(\p{L}[\p{L}\s.-]+?)(?:-\d+|\s+\d+|\d{2,})$""").matchEntire(rawCity)?.let { m ->
                    val stripped = m.groupValues[1].trim()
                    if (stripped.length >= 2) rawCity = stripped
                }
                val isPoland = cc.uppercase() == "PL" || resolvedCountry == "Polska"
                val rawGmina = obj.optString("gmina").takeIf { it.isNotBlank() }
                val resolvedGmina = if (isPoland && rawGmina != null) {
                    if (rawGmina.equals("Jamnik", ignoreCase = true) || rawGmina.contains("Jamnik", ignoreCase = true)) {
                        if (rawCity.equals("Jaszczurowa", ignoreCase = true)) "gmina Mucharz" else null
                    } else if (!rawGmina.startsWith("gmina", ignoreCase = true) && !rawGmina.startsWith("gm.", ignoreCase = true)) {
                        null
                    } else rawGmina
                } else rawGmina
                val resolvedCity = when {
                    (resolvedGmina?.contains("gm. Kozy", ignoreCase = true) == true || resolvedGmina?.contains("gmina Kozy", ignoreCase = true) == true) -> {
                        if (rawCity.equals("Krzemionki", ignoreCase = true) || rawCity.equals("Małe Kozy", ignoreCase = true)) {
                            "Kozy"
                        } else rawCity
                    }
                    (resolvedGmina?.contains("Andrychów", ignoreCase = true) == true && rawCity.equals("Groń", ignoreCase = true)) -> {
                        val st = (obj.optString("street") ?: "").lowercase(Locale.ROOT)
                        when {
                            st.contains("graniczn") || st.contains("kowalczyk") -> "Targanice"
                            st.contains("leśna") || st.contains("lesna") -> "Roczyny"
                            else -> "Brzezinka"
                        }
                    }
                    else -> rawCity
                }
                val rawStreet = obj.optString("street").takeIf { it.isNotBlank() }
                val rawRoadRef = obj.optString("roadRef").takeIf { it.isNotBlank() }
                var cleanStreet = RoadNameNormalizer.sanitizeStreetForCountry(rawStreet, cc) ?: rawStreet
                var cleanRoadRef = RoadNameNormalizer.sanitizeStreetForCountry(rawRoadRef, cc) ?: rawRoadRef

                if (isPoland) {
                    if (resolvedCity.equals("Roczyny", ignoreCase = true) || resolvedCity.equals("Czaniec", ignoreCase = true)) {
                        if (cleanStreet?.contains("DW781", ignoreCase = true) == true || cleanStreet?.contains("DW 781", ignoreCase = true) == true || cleanRoadRef?.contains("781") == true) {
                            cleanStreet = cleanStreet?.let { RoadNameNormalizer.stripParenHighwayRef(it) } ?: cleanStreet
                            cleanRoadRef = null
                        }
                    } else if (resolvedCity.equals("Andrychów", ignoreCase = true)) {
                        if (cleanStreet?.startsWith("Szewska", ignoreCase = true) == true) {
                            cleanStreet = "Szewska"
                            cleanRoadRef = null
                        } else if (cleanStreet?.startsWith("Krakowska", ignoreCase = true) == true && cleanStreet?.contains("DW781", ignoreCase = true) == true) {
                            cleanStreet = "Krakowska (DK52)"
                            cleanRoadRef = "DK52"
                        }
                    } else if (resolvedCity.equals("Sucha Beskidzka", ignoreCase = true)) {
                        if (cleanStreet?.startsWith("Wadowicka", ignoreCase = true) == true && cleanStreet?.contains("DW946", ignoreCase = true) == true) {
                            cleanStreet = "Wadowicka (DK28)"
                            cleanRoadRef = "DK28"
                        }
                    }
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
        sanitizeKnownMismatches(db)
        sanitizePolishHighwayCodesOutsidePoland(db)
        sanitizeDupeParenStreets(db)
        sanitizeFalseHighwayOnLocalNames(db)
        sanitizeCorruptedPolishMunicipalities(db)
        sanitizePoisonedSideStreets(db)
        sanitizeCorruptedRoczynyDw781(db)
        sanitizeGronInGminaAndrychow(db)
        sanitizeAllSuspiciousHighwayAssignments(db)
        sanitizeDegradedLocalityAndCorridorEntries(db)
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

    fun sanitizeCorruptedRoczynyDw781(db: SQLiteDatabase = writableDatabase) {
        try {
            // Roczyny / Czaniec: neither village is traversed by DW781 (DW781 runs in Andrychów).
            // Strip any DW781 from residential streets in Roczyny and Czaniec.
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE ($COL_CITY = 'Roczyny' OR $COL_CITY = 'Czaniec'
                       OR $COL_PL_JSON LIKE '%"city":"Roczyny"%' OR $COL_PL_JSON LIKE '%"city":"Czaniec"%')
                  AND ($COL_STREET LIKE '%DW781%' OR $COL_STREET LIKE '%DW 781%'
                       OR $COL_ROAD_REF LIKE '%781%'
                       OR $COL_PL_JSON LIKE '%DW781%' OR $COL_PL_JSON LIKE '%DW 781%'
                       OR $COL_EN_JSON LIKE '%DW781%' OR $COL_EN_JSON LIKE '%DW 781%')
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val en = deserializePlaceInfo(it.getString(2))
                    val pl = deserializePlaceInfo(it.getString(3))
                    val native = deserializePlaceInfo(it.getString(4))
                    val rawStreet = pl?.street ?: it.getString(1) ?: ""
                    val cleanStreet = RoadNameNormalizer.stripParenHighwayRef(rawStreet)?.trim() ?: rawStreet
                    val enClean = en?.street?.let { s -> RoadNameNormalizer.stripParenHighwayRef(s)?.trim() ?: s } ?: cleanStreet
                    val plClean = pl?.street?.let { s -> RoadNameNormalizer.stripParenHighwayRef(s)?.trim() ?: s } ?: cleanStreet
                    val natClean = native?.street?.let { s -> RoadNameNormalizer.stripParenHighwayRef(s)?.trim() ?: s } ?: cleanStreet
                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_STREET, plClean)
                        putNull(COL_ROAD_REF)
                        if (en != null) put(COL_EN_JSON, serializePlaceInfo(en.copy(street = enClean, roadRef = null)))
                        if (pl != null) put(COL_PL_JSON, serializePlaceInfo(pl.copy(street = plClean, roadRef = null)))
                        if (native != null) put(COL_NATIVE_JSON, serializePlaceInfo(native.copy(street = natClean, roadRef = null)))
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
     * Repairs entries where generic side streets were erroneously merged with highway codes
     * (e.g. `Wiejska (DK52)`, `Podlesie (DK52)`, `Starowiejska (DK52)`) in-place without deleting
     * the spatial cache row, preventing recurring cache deficits.
     */
    fun sanitizePoisonedSideStreets(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_CITY, $COL_STREET, $COL_ROAD_REF, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_STREET LIKE 'Wiejska (DK%'
                   OR $COL_STREET LIKE 'Starowiejska (DK%'
                   OR $COL_STREET LIKE 'Gminna (DK%'
                   OR $COL_STREET LIKE 'Rolna (DK%'
                   OR $COL_STREET LIKE 'Dworska (DK%'
                   OR $COL_STREET LIKE 'Podlesie (DK%'
                   OR $COL_STREET LIKE 'Szkolna (DK%'
                   OR $COL_STREET LIKE 'Polna (DK%'
                   OR $COL_STREET LIKE 'Leśna (DK%'
                   OR $COL_STREET LIKE 'Cicha (DK%'
                   OR $COL_STREET LIKE 'Graniczna (DK%'
                   OR $COL_STREET LIKE 'Ogrodowa (DK%'
                   OR $COL_STREET LIKE 'Boczna (DK%'
                   OR $COL_STREET LIKE 'Przecznia (DK%'
                   OR $COL_PL_JSON LIKE '%"Wiejska (DK%'
                   OR $COL_PL_JSON LIKE '%"Starowiejska (DK%'
                   OR $COL_PL_JSON LIKE '%"Podlesie (DK%'
                   OR $COL_PL_JSON LIKE '%"Szkolna (DK%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val city = it.getString(1) ?: ""
                    val street = it.getString(2) ?: ""
                    val roadRef = it.getString(3)
                    val ref = roadRef?.takeIf { r -> r.isNotBlank() }
                        ?: RoadNameNormalizer.extractHighwayCode(street)
                        ?: "DK52"
                    val cleanStreet = when {
                        ref == "DK52" && (city.equals("Bulowice", ignoreCase = true) || city.equals("Kęty", ignoreCase = true)) -> "Bielska (DK52)"
                        ref == "DK52" && (city.equals("Inwałd", ignoreCase = true) || city.equals("Andrychów", ignoreCase = true)) -> "Wadowicka (DK52)"
                        else -> ref
                    }
                    val en = deserializePlaceInfo(it.getString(4))
                    val pl = deserializePlaceInfo(it.getString(5))
                    val native = deserializePlaceInfo(it.getString(6))
                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_STREET, cleanStreet)
                        put(COL_ROAD_REF, ref)
                        if (en != null) put(COL_EN_JSON, serializePlaceInfo(en.copy(street = cleanStreet, roadRef = ref)))
                        if (pl != null) put(COL_PL_JSON, serializePlaceInfo(pl.copy(street = cleanStreet, roadRef = ref)))
                        if (native != null) put(COL_NATIVE_JSON, serializePlaceInfo(native.copy(street = cleanStreet, roadRef = ref)))
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
        } catch (e: Exception) {
            TelemetryLogger.log("SPATIAL_CACHE", "sanitizePoisonedSideStreets error: ${e.message}")
        }
    }

    /**
     * Purges or corrects hamlets/subdistricts erroneously cached as gminas in Poland
     * (e.g. `Jamnik` -> `gmina Mucharz` for Jaszczurowa, or null for non-gmina strings).
     * Field trip 280 Jaszczurowa 2026-11-01.
     */
    fun sanitizeCorruptedPolishMunicipalities(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_PL_JSON LIKE '%"Jamnik"%'
                   OR $COL_PL_JSON LIKE '%"gmina":"Jamnik"%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val en = deserializePlaceInfo(it.getString(1))
                    val pl = deserializePlaceInfo(it.getString(2))
                    val native = deserializePlaceInfo(it.getString(3))
                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        if (en != null) put(COL_EN_JSON, serializePlaceInfo(en))
                        if (pl != null) put(COL_PL_JSON, serializePlaceInfo(pl))
                        if (native != null) put(COL_NATIVE_JSON, serializePlaceInfo(native))
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
     * Strips glued highway annotations from commemorative / multi-token local names
     * (e.g. `Kardynała Karola Wojtyły (DK52)` → `Kardynała Karola Wojtyły`, clear road_ref).
     * Corridor aliases like `Krakowska (DK52)` are kept. Field trip 249 Czaniec 2026-10-22.
     */
    fun sanitizeFalseHighwayOnLocalNames(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_ROAD_REF, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON, $COL_HIGHWAY_TYPE
                FROM $TABLE_CACHE
                WHERE $COL_STREET LIKE '%(DK%' OR $COL_STREET LIKE '%(DW%'
                   OR $COL_STREET LIKE '%(A%' OR $COL_STREET LIKE '%(S%'
                   OR $COL_ROAD_REF LIKE 'DK%' OR $COL_ROAD_REF LIKE 'DW%'
                   OR $COL_ROAD_REF LIKE 'A%' OR $COL_ROAD_REF LIKE 'S%'
                   OR $COL_PL_JSON LIKE '%(DK%' OR $COL_PL_JSON LIKE '%(DW%'
                   OR $COL_PL_JSON LIKE '%(A%' OR $COL_PL_JSON LIKE '%(S%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val street = it.getString(1) ?: continue
                    val roadRef = it.getString(2)
                    val base = RoadNameNormalizer.stripParenHighwayRef(street)?.trim() ?: street
                    val pl = deserializePlaceInfo(it.getString(4))
                    val highwayType = it.getString(6) ?: pl?.highwayType
                    val isVerifiedMajor = highwayType != null && RoadNameNormalizer.isMajorHighwayType(highwayType)
                    val isResidential = highwayType != null && RoadNameNormalizer.isResidentialHighwayType(highwayType)
                    if (isVerifiedMajor && !isResidential) continue
                    if (!isResidential && roadRef != null && RoadNameNormalizer.isMajorHighway(highwayType, roadRef) && RoadNameNormalizer.isMajorRoad(base, roadRef)) continue

                    val hasParen = street.contains('(')
                    val hasHighwayRef = !roadRef.isNullOrBlank() &&
                        RoadNameNormalizer.isHighwayCodeOnly(roadRef.trim())
                    if (!hasParen && !hasHighwayRef) continue
                    val en = deserializePlaceInfo(it.getString(3))
                    val native = deserializePlaceInfo(it.getString(5))
                    fun PlaceInfo.cleaned(): PlaceInfo {
                        val s = RoadNameNormalizer.stripParenHighwayRef(this.street)?.trim() ?: this.street
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
     * Purges or rewrites hamlet/settlement Groń in gmina Andrychów (teryt:rm = 04, osada wsi Brzezinka)
     * to its official enclosing administrative village:
     * - Targanice (ul. Graniczna, ul. Tadeusza Kowalczyka, or lng >= 19.3216)
     * - Roczyny (ul. Leśna, or lat >= 49.8275 && lng < 19.305)
     * - Brzezinka (ul. Podgórska, ul. Pogodna, ul. Legionistów, ul. Podhalańska, etc.)
     */
    fun sanitizeGronInGminaAndrychow(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_STREET, $COL_LAT, $COL_LNG, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_CITY = 'Groń'
                   OR $COL_PL_JSON LIKE '%"city":"Groń"%'
                   OR $COL_PL_JSON LIKE '%"Groń"%'
                """.trimIndent(),
                null
            )
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val street = it.getString(1) ?: ""
                    val lat = it.getDouble(2)
                    val lng = it.getDouble(3)
                    val en = deserializePlaceInfo(it.getString(4))
                    val pl = deserializePlaceInfo(it.getString(5))
                    val native = deserializePlaceInfo(it.getString(6))

                    val gmina = pl?.gmina ?: en?.gmina ?: native?.gmina
                    if (gmina != null && !gmina.contains("Andrychów", ignoreCase = true)) {
                        continue // Skip other Groń (e.g. Groń in gmina Bukowina Tatrzańska)
                    }

                    val stLow = street.lowercase(Locale.ROOT)
                    val newCity = when {
                        stLow.contains("graniczn") || stLow.contains("kowalczyk") || lng >= 19.3216 -> "Targanice"
                        stLow.contains("leśna") || stLow.contains("lesna") || (lat >= 49.8275 && lng < 19.305) -> "Roczyny"
                        else -> "Brzezinka"
                    }

                    updates.add(ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_CITY, newCity)
                        if (en != null) put(COL_EN_JSON, serializePlaceInfo(en.copy(city = newCity)))
                        if (pl != null) put(COL_PL_JSON, serializePlaceInfo(pl.copy(city = newCity)))
                        if (native != null) put(COL_NATIVE_JSON, serializePlaceInfo(native.copy(city = newCity)))
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
     * Comprehensive database scan and sanitization:
     * 1. Resolves split-brain states where top-level SQLite columns differ from JSON payloads.
     * 2. Purges false highway code attachments on side streets and misattributed roads across all recorded regions.
     * 3. Strips stray house numbers from stored street names.
     */
    fun sanitizeAllSuspiciousHighwayAssignments(db: SQLiteDatabase = writableDatabase) {
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_GRID_KEY, $COL_CITY, $COL_STREET, $COL_ROAD_REF, $COL_HIGHWAY_TYPE, $COL_EN_JSON, $COL_PL_JSON, $COL_NATIVE_JSON
                FROM $TABLE_CACHE
                WHERE $COL_STREET LIKE '%(DK%' OR $COL_STREET LIKE '%(DW%'
                   OR $COL_STREET LIKE '%(A%' OR $COL_STREET LIKE '%(S%'
                   OR $COL_ROAD_REF LIKE 'DK%' OR $COL_ROAD_REF LIKE 'DW%'
                   OR $COL_ROAD_REF LIKE 'A%' OR $COL_ROAD_REF LIKE 'S%'
                   OR $COL_ROAD_REF LIKE '9%' OR $COL_ROAD_REF LIKE '5%' OR $COL_ROAD_REF LIKE '7%' OR $COL_ROAD_REF LIKE '2%'
                   OR $COL_PL_JSON LIKE '%(DK%' OR $COL_PL_JSON LIKE '%(DW%'
                   OR $COL_PL_JSON LIKE '%(A%' OR $COL_PL_JSON LIKE '%(S%'
                   OR $COL_STREET GLOB '* [0-9]*'
                   OR $COL_STREET IN ('Bielska', 'Krakowska', 'Beskidzka')
                """.trimIndent(),
                null
            )
            try {
                db.execSQL("DELETE FROM $TABLE_CACHE WHERE $COL_CITY = 'Kozy' AND $COL_STREET LIKE 'Wspólna%' AND ($COL_LAT BETWEEN 49.8470 AND 49.8476) AND ($COL_LNG BETWEEN 19.1480 AND 19.1488)")
            } catch (_: Exception) {}
            val updates = mutableListOf<ContentValues>()
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val colCity = it.getString(1) ?: ""
                    val colStreet = it.getString(2) ?: ""
                    val colRef = it.getString(3)
                    val hwyType = it.getString(4)
                    val en = deserializePlaceInfo(it.getString(5))
                    val pl = deserializePlaceInfo(it.getString(6))
                    val native = deserializePlaceInfo(it.getString(7))

                    val city = pl?.city ?: colCity
                    var st = pl?.street ?: colStreet
                    var ref = pl?.roadRef ?: colRef
                    val countryCode = pl?.countryCode ?: en?.countryCode ?: "PL"

                    var modified = false

                    // Clean known false highway pairs
                    when {
                        city.equals("Roczyny", ignoreCase = true) || city.equals("Czaniec", ignoreCase = true) -> {
                            if (st.contains("DW781", ignoreCase = true) || st.contains("DW 781", ignoreCase = true) || ref?.contains("781") == true) {
                                st = RoadNameNormalizer.stripParenHighwayRef(st)?.trim() ?: st
                                ref = null
                                modified = true
                            }
                        }
                        city.equals("Andrychów", ignoreCase = true) -> {
                            if (st.startsWith("Szewska", ignoreCase = true)) {
                                st = "Szewska"
                                ref = null
                                modified = true
                            } else if (st.startsWith("Krakowska", ignoreCase = true) && (st.contains("DW781", ignoreCase = true) || ref?.contains("781") == true)) {
                                st = "Krakowska (DK52)"
                                ref = "DK52"
                                modified = true
                            }
                        }
                        city.equals("Sucha Beskidzka", ignoreCase = true) -> {
                            if (st.startsWith("Wadowicka", ignoreCase = true) && (st.contains("DW946", ignoreCase = true) || ref?.contains("946") == true)) {
                                st = "Wadowicka (DK28)"
                                ref = "DK28"
                                modified = true
                            }
                        }
                        city.equals("Łękawica", ignoreCase = true) -> {
                            if (st.contains("Alaska", ignoreCase = true) || st.startsWith("Beskidzka", ignoreCase = true)) {
                                st = RoadNameNormalizer.stripParenHighwayRef(st)?.trim() ?: st
                                ref = null
                                modified = true
                            }
                        }
                        city.equals("Las", ignoreCase = true) && st.startsWith("Soliska", ignoreCase = true) -> {
                            st = "Soliska"
                            ref = null
                            modified = true
                        }
                        city.equals("Gilowice", ignoreCase = true) && st.startsWith("Starodworska", ignoreCase = true) -> {
                            st = "Starodworska"
                            ref = null
                            modified = true
                        }
                        city.equals("Okrajnik", ignoreCase = true) && st.startsWith("Strażacka", ignoreCase = true) -> {
                            st = "Strażacka"
                            ref = null
                            modified = true
                        }
                        city.equals("Czernichów", ignoreCase = true) && st.startsWith("Stolarska", ignoreCase = true) -> {
                            st = "Stolarska"
                            ref = null
                            modified = true
                        }
                        city.equals("Międzybrodzie Bialskie", ignoreCase = true) && st.startsWith("Strażacka", ignoreCase = true) -> {
                            st = "Strażacka"
                            ref = null
                            modified = true
                        }
                        city.equals("Jabłonka", ignoreCase = true) && st.startsWith("Krakowska", ignoreCase = true) && (st.contains("DW957", ignoreCase = true) || ref?.contains("957") == true) -> {
                            st = "Krakowska (DK7)"
                            ref = "DK7"
                            modified = true
                        }
                    }

                    // Check residential / non-major highway with attached highway code
                    if (!modified && (ref != null || st.contains('('))) {
                        val base = RoadNameNormalizer.stripParenHighwayRef(st)?.trim() ?: st
                        val isResidential = hwyType != null && RoadNameNormalizer.isResidentialHighwayType(hwyType)
                        if (isResidential) {
                            st = base
                            ref = null
                            modified = true
                        }
                    }

                    // Strip house numbers from stored street & preserve/format major highway corridors
                    val cleanBase = RoadNameNormalizer.extractBaseStreet(st).trim()
                    val confirmedCorridorRef = when {
                        (city.equals("Bujaków", ignoreCase = true) || city.equals("Kozy", ignoreCase = true)) && cleanBase.equals("Bielska", ignoreCase = true) -> "DK52"
                        (city.equals("Kozy", ignoreCase = true) || city.equals("Andrychów", ignoreCase = true) || city.equals("Bielsko-Biała", ignoreCase = true)) && cleanBase.equals("Krakowska", ignoreCase = true) -> "DK52"
                        city.equals("Andrychów", ignoreCase = true) && cleanBase.equals("Beskidzka", ignoreCase = true) -> "DW781"
                        else -> null
                    }
                    if (confirmedCorridorRef != null && (ref == null || ref != confirmedCorridorRef)) {
                        ref = confirmedCorridorRef
                        st = RoadNameNormalizer.formatStreetDisplay(cleanBase, ref, countryCode) ?: st
                        modified = true
                    } else if (ref != null && RoadNameNormalizer.isMajorRoad(st, ref)) {
                        val canonical = RoadNameNormalizer.formatStreetDisplay(cleanBase, ref, countryCode) ?: st
                        if (canonical != st) {
                            st = canonical
                            modified = true
                        }
                    } else if (cleanBase.isNotEmpty() && cleanBase != st && !cleanBase.contains('(')) {
                        st = cleanBase
                        modified = true
                    }

                    // Split-brain check
                    val isSplitBrain = (colStreet != st) || (colRef != ref)
                    if (modified || isSplitBrain) {
                        val newEn = en?.copy(street = st, roadRef = ref)
                        val newPl = pl?.copy(street = st, roadRef = ref)
                        val newNat = native?.copy(street = st, roadRef = ref)
                        updates.add(ContentValues().apply {
                            put(COL_GRID_KEY, key)
                            put(COL_STREET, st)
                            if (ref != null) put(COL_ROAD_REF, ref) else putNull(COL_ROAD_REF)
                            if (newEn != null) put(COL_EN_JSON, serializePlaceInfo(newEn))
                            if (newPl != null) put(COL_PL_JSON, serializePlaceInfo(newPl))
                            if (newNat != null) put(COL_NATIVE_JSON, serializePlaceInfo(newNat))
                        })
                    }
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
     * Collapses doubled annotations like `Krakowska (DK52) (DK52)`.
     * Note: Bare highway codes (`S1`, `A4`, `DK52`, `DW957`) must NEVER be deleted; they represent valid
     * canonical road names on motorways, expressways, and regional corridors without residential street names.
     */
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

    @Deprecated("Renamed to sanitizeDupeParenStreets; bare highways are now preserved permanently")
    fun sanitizeBareHighwayAndDupeParenStreets(db: SQLiteDatabase = writableDatabase) {
        sanitizeDupeParenStreets(db)
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
                   OR (street LIKE '%Górska%' AND latitude BETWEEN 49.8508 AND 49.8525 AND longitude BETWEEN 19.2840 AND 19.2860)
                   OR ((latitude BETWEEN 49.8350 AND 49.8550 AND longitude BETWEEN 19.0450 AND 19.0750)
                       AND (street LIKE '%Braterska%' 
                         OR street LIKE '%Chłopickiego%' 
                         OR street LIKE '%Baczyńskiego%' 
                         OR street LIKE '%Kryształowa%' 
                         OR street LIKE '%Czerwona%' 
                         OR street LIKE '%Bukietowa%'))
                   OR city IN ('Likwidacja', 'Serwis', 'Powiat', 'koło', 'Unknown City', '--')
            """.trimIndent())

            // Self-heal valid ul. Górska (Czaniec) cells (east of ul. Zielona, lng >= 19.2874, lat <= 49.8506)
            // that were previously wiped by the overbroad Górska bounding box.
            val gorskaInfo = PlaceInfo(
                city = "Czaniec",
                street = "Górska",
                roadRef = null,
                gmina = "gmina Porąbka",
                powiat = "powiat bielski",
                voivodeship = "woj. śląskie",
                country = "Polska",
                countryCode = "PL"
            )
            val gorskaJson = serializePlaceInfo(gorskaInfo)
            val gorskaCells = listOf(
                Pair(49.850192, 19.2874421),
                Pair(49.850206, 19.2875005),
                Pair(49.8502311, 19.2876414),
                Pair(49.8502587, 19.2874879),
                Pair(49.8503392, 19.2875532),
                Pair(49.8503022, 19.2876865),
                Pair(49.8503679, 19.2874832),
                Pair(49.8504128, 19.2875784),
                Pair(49.8503909, 19.2877192),
                Pair(49.8505282, 19.2875026),
                Pair(49.8505041, 19.2876302),
                Pair(49.8505347, 19.2876726),
                Pair(49.8504596, 19.2877677),
                Pair(49.8505548, 19.2876249)
            )
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                for ((lat, lng) in gorskaCells) {
                    val key = toGridKey(lat, lng)
                    val cv = ContentValues().apply {
                        put(COL_GRID_KEY, key)
                        put(COL_LAT, lat)
                        put(COL_LNG, lng)
                        put(COL_CITY, "Czaniec")
                        put(COL_STREET, "Górska")
                        putNull(COL_ROAD_REF)
                        put(COL_EN_JSON, gorskaJson)
                        put(COL_PL_JSON, gorskaJson)
                        put(COL_NATIVE_JSON, gorskaJson)
                        put(COL_TIMESTAMP, now)
                    }
                    db.insertWithOnConflict(TABLE_CACHE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
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

    fun sanitizeDegradedLocalityAndCorridorEntries(db: SQLiteDatabase = writableDatabase) {
        try {
            // Delete corrupt short junk
            db.execSQL("DELETE FROM $TABLE_CACHE WHERE $COL_CITY = 'z' OR length($COL_CITY) < 2")

            // Clean abbreviated Międzybrodzie Kob. -> Międzybrodzie Bialskie
            db.execSQL(
                """
                UPDATE $TABLE_CACHE SET $COL_CITY = 'Międzybrodzie Bialskie',
                    $COL_EN_JSON = REPLACE(REPLACE($COL_EN_JSON, '"city":"Międzybrodzie Kob."', '"city":"Międzybrodzie Bialskie"'), '"city":"Międzybrodzie Kobiernickie"', '"city":"Międzybrodzie Bialskie"'),
                    $COL_PL_JSON = REPLACE(REPLACE($COL_PL_JSON, '"city":"Międzybrodzie Kob."', '"city":"Międzybrodzie Bialskie"'), '"city":"Międzybrodzie Kobiernickie"', '"city":"Międzybrodzie Bialskie"'),
                    $COL_NATIVE_JSON = REPLACE(REPLACE($COL_NATIVE_JSON, '"city":"Międzybrodzie Kob."', '"city":"Międzybrodzie Bialskie"'), '"city":"Międzybrodzie Kobiernickie"', '"city":"Międzybrodzie Bialskie"')
                WHERE $COL_CITY LIKE 'Międzybrodzie Kob%' OR $COL_PL_JSON LIKE '%Międzybrodzie Kob%'
                """.trimIndent()
            )

            // Clean Porąbka-1 -> Porąbka
            db.execSQL(
                """
                UPDATE $TABLE_CACHE SET $COL_CITY = 'Porąbka',
                    $COL_EN_JSON = REPLACE($COL_EN_JSON, '"city":"Porąbka-1"', '"city":"Porąbka"'),
                    $COL_PL_JSON = REPLACE($COL_PL_JSON, '"city":"Porąbka-1"', '"city":"Porąbka"'),
                    $COL_NATIVE_JSON = REPLACE($COL_NATIVE_JSON, '"city":"Porąbka-1"', '"city":"Porąbka"')
                WHERE $COL_CITY = 'Porąbka-1' OR $COL_PL_JSON LIKE '%"city":"Porąbka-1"%'
                """.trimIndent()
            )

            // Clean Zawoja1579 -> Zawoja
            db.execSQL(
                """
                UPDATE $TABLE_CACHE SET $COL_CITY = 'Zawoja',
                    $COL_EN_JSON = REPLACE($COL_EN_JSON, '"city":"Zawoja1579"', '"city":"Zawoja"'),
                    $COL_PL_JSON = REPLACE($COL_PL_JSON, '"city":"Zawoja1579"', '"city":"Zawoja"'),
                    $COL_NATIVE_JSON = REPLACE($COL_NATIVE_JSON, '"city":"Zawoja1579"', '"city":"Zawoja"')
                WHERE $COL_CITY = 'Zawoja1579' OR $COL_PL_JSON LIKE '%"city":"Zawoja1579"%'
                """.trimIndent()
            )

            // Clean Bielsko-Biala -> Bielsko-Biała
            db.execSQL(
                """
                UPDATE $TABLE_CACHE SET
                    $COL_PL_JSON = REPLACE($COL_PL_JSON, '"city":"Bielsko-Biala"', '"city":"Bielsko-Biała"'),
                    $COL_NATIVE_JSON = REPLACE($COL_NATIVE_JSON, '"city":"Bielsko-Biala"', '"city":"Bielsko-Biała"')
                WHERE $COL_PL_JSON LIKE '%"city":"Bielsko-Biala"%' OR $COL_NATIVE_JSON LIKE '%"city":"Bielsko-Biala"%'
                """.trimIndent()
            )

            // Purge degraded highway_type IS NULL AND road_ref IS NULL rows from the 2026-11-09 outage
            // on Żywiecka / Bielska in Międzybrodzie Bialskie and Porąbka so they are refreshed cleanly
            db.execSQL(
                """
                DELETE FROM $TABLE_CACHE
                WHERE $COL_HIGHWAY_TYPE IS NULL AND $COL_ROAD_REF IS NULL
                  AND ($COL_CITY = 'Międzybrodzie Bialskie' OR $COL_CITY = 'Porąbka')
                  AND ($COL_STREET LIKE 'Żywiecka%' OR $COL_STREET LIKE 'Bielska%')
                  AND $COL_TIMESTAMP BETWEEN 1794236000000 AND 1794238000000
                """.trimIndent()
            )

            // Purge Sept 20-21 side-street snaps on DW948 corridor with highway_type IS NULL
            db.execSQL(
                """
                DELETE FROM $TABLE_CACHE
                WHERE $COL_HIGHWAY_TYPE IS NULL AND $COL_ROAD_REF IS NULL
                  AND ($COL_STREET = 'Zamkowa' OR $COL_STREET = 'Miła' OR $COL_STREET = 'Źródlana')
                  AND ($COL_CITY = 'Międzybrodzie Bialskie' OR $COL_CITY = 'Międzybrodzie Kob.')
                """.trimIndent()
            )
        } catch (_: Exception) {}
    }

    /**
     * Finds the nearest OSM-verified locality (highway_type IS NOT NULL) within [radiusM] meters.
     */
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
