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

    fun get(lat: Double, lng: Double, maxAgeMs: Long? = null): MultiLanguagePlaceInfo? {
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
                if (maxAgeMs != null) {
                    val timestamp = it.getLong(3)
                    if (System.currentTimeMillis() - timestamp > maxAgeMs) {
                        return null
                    }
                }
                val enJson = it.getString(0)
                val plJson = it.getString(1)
                val nativeJson = it.getString(2)
                val en = deserializePlaceInfo(enJson)
                val pl = deserializePlaceInfo(plJson)
                val native = deserializePlaceInfo(nativeJson)
                if (en != null && pl != null && native != null) {
                    return MultiLanguagePlaceInfo(en = en, pl = pl, native = native)
                }
            }
        }
        return null
    }

    fun put(lat: Double, lng: Double, info: MultiLanguagePlaceInfo) {
        val key = toGridKey(lat, lng)
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_GRID_KEY, key)
            put(COL_LAT, lat)
            put(COL_LNG, lng)
            put(COL_CITY, info.pl.city)
            put(COL_STREET, info.pl.street)
            put(COL_ROAD_REF, info.pl.roadRef)
            put(COL_EN_JSON, serializePlaceInfo(info.en))
            put(COL_PL_JSON, serializePlaceInfo(info.pl))
            put(COL_NATIVE_JSON, serializePlaceInfo(info.native))
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

    private fun serializePlaceInfo(place: PlaceInfo): String {
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

    private fun deserializePlaceInfo(jsonStr: String?): PlaceInfo? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            PlaceInfo(
                city = obj.optString("city", "Unknown City"),
                street = obj.optString("street").takeIf { it.isNotBlank() },
                roadRef = obj.optString("roadRef").takeIf { it.isNotBlank() },
                gmina = obj.optString("gmina").takeIf { it.isNotBlank() },
                powiat = obj.optString("powiat").takeIf { it.isNotBlank() },
                voivodeship = obj.optString("voivodeship", "Unknown Region"),
                country = obj.optString("country", "Unknown Country"),
                countryCode = obj.optString("countryCode", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}
