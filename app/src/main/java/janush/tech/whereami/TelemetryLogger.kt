package janush.tech.whereami

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * High-performance, low-overhead telemetry recorder for WhereIAm.
 * Captures GPS fixes, geocoding decisions, hysteresis transitions, and trip state changes.
 */
object TelemetryLogger {

    private const val MAX_MEMORY_EVENTS = 500
    private val eventQueue = ConcurrentLinkedQueue<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isEnabled = true

    fun log(category: String, message: String, extras: Map<String, Any?> = emptyMap()) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
        val obj = JSONObject().apply {
            put("timestamp", now)
            put("datetime", timeStr)
            put("category", category)
            put("message", message)
            if (extras.isNotEmpty()) {
                val extrasObj = JSONObject()
                extras.forEach { (k, v) -> extrasObj.put(k, v ?: JSONObject.NULL) }
                put("extras", extrasObj)
            }
        }
        eventQueue.add(obj)
        while (eventQueue.size > MAX_MEMORY_EVENTS) {
            eventQueue.poll()
        }
    }

    fun logGps(lat: Double, lng: Double, speedKmh: Float, bearing: Float?, accuracy: Float?) {
        log(
            category = "GPS",
            message = String.format(Locale.US, "%.5f, %.5f (%.1f km/h)", lat, lng, speedKmh),
            extras = mapOf(
                "lat" to lat,
                "lng" to lng,
                "speed_kmh" to speedKmh,
                "bearing" to bearing,
                "accuracy" to accuracy
            )
        )
    }

    fun logHysteresis(event: String, fromCity: String?, toCity: String?, details: String) {
        log(
            category = "HYSTERESIS",
            message = "$event: '$fromCity' -> '$toCity' ($details)",
            extras = mapOf("from" to fromCity, "to" to toCity, "details" to details)
        )
    }

    fun logTrip(event: String, tripId: Long, details: String) {
        log(
            category = "TRIP",
            message = "Trip #$tripId $event: $details",
            extras = mapOf("trip_id" to tripId, "event" to event, "details" to details)
        )
    }

    fun exportTelemetry(context: Context): File {
        val dir = File(context.cacheDir, "telemetry_exports").apply { mkdirs() }
        val file = File(dir, "where_i_am_telemetry_${System.currentTimeMillis()}.json")
        val array = JSONArray()
        eventQueue.forEach { array.put(it) }
        val root = JSONObject().apply {
            put("exported_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("total_events", array.length())
            put("events", array)
        }
        file.writeText(root.toString(2))
        return file
    }

    fun shareTelemetry(context: Context) {
        scope.launch {
            try {
                val file = exportTelemetry(context)
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "WhereIAm Telemetry Log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "Share Telemetry Log").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                android.util.Log.e("TelemetryLogger", "Failed to share telemetry", e)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Export error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
