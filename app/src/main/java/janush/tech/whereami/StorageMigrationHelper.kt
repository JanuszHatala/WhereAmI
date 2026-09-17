package janush.tech.whereami

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Storage and SharedPreferences migration utility for WhereAmI.
 *
 * Ensures seamless, zero-data-loss transition from legacy "where_i_am_*" files
 * to the canonical "where_am_i_*" database and preference stores.
 * Also sanitizes corrupted municipality cache entries (e.g. Bielsko-Biała inheriting Porąbka).
 */
object StorageMigrationHelper {

    const val LEGACY_DB_NAME = "where_i_am_trips.db"
    const val CANONICAL_DB_NAME = "where_am_i_trips.db"

    private val PREFS_MIGRATION_MAP = mapOf(
        "where_i_am_prefs" to "where_am_i_prefs",
        "where_i_am_trip_prefs" to "where_am_i_trip_prefs",
        "where_i_am_live_share_prefs" to "where_am_i_live_share_prefs",
        "where_i_am_power_prefs" to "where_am_i_power_prefs",
        "where_i_am_ui_prefs" to "where_am_i_ui_prefs",
        "whereiam_map_prefs" to "where_am_i_map_prefs"
    )

    /**
     * Atomically migrates the SQLite database file (and journal/WAL/SHM sidecars)
     * if the legacy file exists and canonical file does not yet exist.
     */
    fun migrateDatabaseIfNeeded(context: Context) {
        try {
            val targetDb = context.getDatabasePath(CANONICAL_DB_NAME)
            val legacyDb = context.getDatabasePath(LEGACY_DB_NAME)

            if (!targetDb.exists() && legacyDb.exists()) {
                val dbDir = targetDb.parentFile ?: legacyDb.parentFile
                if (dbDir != null && !dbDir.exists()) {
                    dbDir.mkdirs()
                }

                val extensions = listOf("", "-wal", "-shm", "-journal")
                for (ext in extensions) {
                    val srcFile = File(legacyDb.parentFile, "$LEGACY_DB_NAME$ext")
                    val dstFile = File(dbDir, "$CANONICAL_DB_NAME$ext")
                    if (srcFile.exists() && !dstFile.exists()) {
                        copyFile(srcFile, dstFile)
                        TelemetryLogger.log("MIGRATION", "Migrated DB sidecar: ${srcFile.name} -> ${dstFile.name}")
                    }
                }
                TelemetryLogger.log("MIGRATION", "Successfully migrated database $LEGACY_DB_NAME -> $CANONICAL_DB_NAME")
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to migrate database: ${e.message}")
        }
    }

    /**
     * Copies all key-value entries from legacy SharedPreferences stores to canonical stores,
     * sanitizing any poisoned locality gmina entries.
     */
    fun migratePreferencesIfNeeded(context: Context) {
        try {
            for ((legacyName, canonicalName) in PREFS_MIGRATION_MAP) {
                val legacyPrefs = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
                val canonicalPrefs = context.getSharedPreferences(canonicalName, Context.MODE_PRIVATE)

                val legacyAll = legacyPrefs.all
                if (legacyAll.isNotEmpty() && canonicalPrefs.all.isEmpty()) {
                    val editor = canonicalPrefs.edit()
                    for ((key, value) in legacyAll) {
                        // Sanitize poisoned gmina keys (e.g. loc_gmina_bielsko-biała = "gmina Porąbka")
                        if (key.startsWith("loc_gmina_")) {
                            val locality = key.removePrefix("loc_gmina_").lowercase()
                            val gminaStr = value as? String ?: ""
                            // Suppress poisoned keys where city with county rights inherited a rural gmina
                            if (locality == "bielsko-biała" && gminaStr.contains("porąbka", ignoreCase = true)) {
                                TelemetryLogger.log("MIGRATION", "Purged poisoned cache key: $key -> $gminaStr")
                                continue
                            }
                            // Strip invalid gminas that don't belong
                            if (gminaStr.isBlank()) continue
                        }

                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is String -> editor.putString(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                editor.putStringSet(key, value as? Set<String>)
                            }
                        }
                    }
                    editor.apply()
                    TelemetryLogger.log("MIGRATION", "Migrated preferences: $legacyName -> $canonicalName (${legacyAll.size} entries)")
                }
            }

            // Always ensure poisoned loc_gmina_bielsko-biała is purged from canonical prefs if present
            val canonicalWhereAmIPrefs = context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
            if (canonicalWhereAmIPrefs.contains("loc_gmina_bielsko-biała")) {
                val cached = canonicalWhereAmIPrefs.getString("loc_gmina_bielsko-biała", "") ?: ""
                if (cached.contains("porąbka", ignoreCase = true)) {
                    canonicalWhereAmIPrefs.edit().remove("loc_gmina_bielsko-biała").apply()
                    TelemetryLogger.log("MIGRATION", "Purged poisoned loc_gmina_bielsko-biała from where_am_i_prefs")
                }
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to migrate preferences: ${e.message}")
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inStream ->
            FileOutputStream(dst).use { outStream ->
                inStream.channel.transferTo(0, inStream.channel.size(), outStream.channel)
            }
        }
    }
}
