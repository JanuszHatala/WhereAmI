package janush.tech.whereami

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "where_i_am_trips.db"
        private const val DATABASE_VERSION = 5

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

        fun cleanPartSuffix(title: String): String {
            return title.replace(Regex("""\s*[-–(]\s*Part\s*\d+\)?.*""", RegexOption.IGNORE_CASE), "").trim()
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
                $COL_PAUSES_JSON TEXT DEFAULT '[]'
            )
        """.trimIndent()
        db.execSQL(createTripsTable)

        createSavedPlacesTable(db)
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
                $COL_SP_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createPlacesTable)
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
    }

    fun insertTrip(trip: TripRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, trip.title)
            put(COL_START_TIME, trip.startTime)
            put(COL_END_TIME, trip.endTime)
            put(COL_DISTANCE, trip.distanceMeters)
            put(COL_MAX_SPEED, trip.maxSpeedKmh)
            put(COL_AVG_SPEED, trip.avgSpeedKmh)
            put(COL_IS_AUTO, if (trip.isAutoDetected) 1 else 0)
            put(COL_ACTIVITY_PROFILE, trip.activityProfile.name)
            put(COL_POINTS_JSON, pointsToJson(trip.points))
            put(COL_PLACES_JSON, placesToJson(trip.placesVisited))
            put(COL_PAUSES_JSON, pausesToJson(trip.pauses))
        }
        return db.insert(TABLE_TRIPS, null, values)
    }

    fun updateTrip(trip: TripRecord) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, trip.title)
            put(COL_START_TIME, trip.startTime)
            put(COL_END_TIME, trip.endTime)
            put(COL_DISTANCE, trip.distanceMeters)
            put(COL_MAX_SPEED, trip.maxSpeedKmh)
            put(COL_AVG_SPEED, trip.avgSpeedKmh)
            put(COL_IS_AUTO, if (trip.isAutoDetected) 1 else 0)
            put(COL_ACTIVITY_PROFILE, trip.activityProfile.name)
            put(COL_POINTS_JSON, pointsToJson(trip.points))
            put(COL_PLACES_JSON, placesToJson(trip.placesVisited))
            put(COL_PAUSES_JSON, pausesToJson(trip.pauses))
        }
        db.update(TABLE_TRIPS, values, "$COL_ID = ?", arrayOf(trip.id.toString()))
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
            points = jsonToPoints(pointsJson),
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
        db.delete(TABLE_TRIPS, "$COL_ID = ?", arrayOf(id.toString()))
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

    fun mergeTrips(tripIds: List<Long>): Long {
        if (tripIds.size < 2) return tripIds.firstOrNull() ?: 0L
        val tripsToMerge = getTripsByIds(tripIds)
        if (tripsToMerge.isEmpty()) return 0L

        val sorted = tripsToMerge.sortedBy { it.startTime }
        val earliest = sorted.first()
        val latest = sorted.last()

        val allPoints = mutableListOf<GeoPoint>()
        val allPlaces = mutableListOf<VisitedPlace>()
        var totalDist = 0.0
        var maxSpd = 0f
        var totalDurationSec = 0.0
        var weightedSpeedSum = 0.0

        for (trip in sorted) {
            allPoints.addAll(trip.points)
            for (p in trip.placesVisited) {
                val last = allPlaces.lastOrNull()?.placeName
                if (last == null || !last.equals(p.placeName, ignoreCase = true)) {
                    allPlaces.add(p)
                }
            }
            totalDist += trip.distanceMeters
            maxSpd = maxOf(maxSpd, trip.maxSpeedKmh)
            val dur = (((trip.endTime ?: (trip.startTime + 60000L)) - trip.startTime) / 1000.0).coerceAtLeast(1.0)
            totalDurationSec += dur
            weightedSpeedSum += (trip.avgSpeedKmh * dur)
        }

        val overallAvgSpeed = if (totalDurationSec > 0) (weightedSpeedSum / totalDurationSec).toFloat() else earliest.avgSpeedKmh
        val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(earliest.startTime))
        val mergedTitle = if (earliest.title.isNotBlank()) "${earliest.title} (Merged)" else "$dateStr • Merged Trips (${sorted.size})"

        val mergedTrip = TripRecord(
            title = mergedTitle,
            startTime = earliest.startTime,
            endTime = latest.endTime ?: latest.startTime,
            distanceMeters = totalDist,
            maxSpeedKmh = maxSpd,
            avgSpeedKmh = overallAvgSpeed,
            isAutoDetected = earliest.isAutoDetected,
            points = allPoints,
            placesVisited = allPlaces
        )

        val newId = insertTrip(mergedTrip)

        // Remove original fragmented trips
        val db = writableDatabase
        val placeholders = tripIds.joinToString(",") { "?" }
        db.delete(TABLE_TRIPS, "$COL_ID IN ($placeholders)", tripIds.map { it.toString() }.toTypedArray())

        return newId
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
                        createdAt = createdAt
                    )
                )
            }
        }
        return list
    }

    private fun pointsToJson(points: List<GeoPoint>): String {
        val array = JSONArray()
        for (p in points) {
            val obj = JSONObject().apply {
                put("lat", p.latitude)
                put("lng", p.longitude)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToPoints(jsonStr: String): List<GeoPoint> {
        val list = mutableListOf<GeoPoint>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(GeoPoint(obj.getDouble("lat"), obj.getDouble("lng")))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun placesToJson(places: List<VisitedPlace>): String {
        val array = JSONArray()
        for (p in places) {
            val obj = JSONObject().apply {
                put("name", p.placeName)
                put("sub", p.hierarchySubtitle)
                put("time", p.timestamp)
                put("lat", p.latitude)
                put("lng", p.longitude)
                put("dist", p.distanceAtEntryMeters)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToPlaces(jsonStr: String): List<VisitedPlace> {
        val list = mutableListOf<VisitedPlace>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    VisitedPlace(
                        placeName = obj.getString("name"),
                        hierarchySubtitle = obj.optString("sub", ""),
                        timestamp = obj.getLong("time"),
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lng"),
                        distanceAtEntryMeters = obj.getDouble("dist")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun splitTripAtPause(tripId: Long, pauseIndex: Int): Pair<Long, Long>? {
        val trip = getTripsByIds(listOf(tripId)).firstOrNull() ?: return null
        if (pauseIndex !in trip.pauses.indices) return null
        val pause = trip.pauses[pauseIndex]
        val splitIdx = pause.pointIndex.coerceIn(1, (trip.points.size - 1).coerceAtLeast(1))

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
        updateTrip(trip1)

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
        val trip2Id = insertTrip(trip2)

        return Pair(trip.id, trip2Id)
    }

    private fun pausesToJson(pauses: List<TripPause>): String {
        val array = JSONArray()
        for (p in pauses) {
            val obj = JSONObject().apply {
                put("start", p.startTime)
                if (p.endTime != null) put("end", p.endTime)
                put("lat", p.latitude)
                put("lng", p.longitude)
                put("dur", p.durationMs)
                put("idx", p.pointIndex)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToPauses(jsonStr: String): List<TripPause> {
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
                        pointIndex = obj.optInt("idx", 0)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}

