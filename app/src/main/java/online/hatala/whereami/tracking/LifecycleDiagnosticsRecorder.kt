package online.hatala.whereami.tracking

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Persistent append-only JSONL recorder of critical app lifecycle events
 * (trip start/stop/pause/resume, motion bursts, sleep transitions, profile switches).
 *
 * Stored in `filesDir/diagnostics/lifecycle_events.jsonl` and pulled by `pull_device_data.sh`.
 */
object LifecycleDiagnosticsRecorder {

    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L // 2 MB
    private var writer: BufferedWriter? = null
    private var logFile: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            try {
                val dir = File(context.filesDir, "diagnostics")
                dir.mkdirs()
                val f = File(dir, "lifecycle_events.jsonl")
                logFile = f
                if (f.length() > MAX_FILE_BYTES) {
                    val bak = File(dir, "lifecycle_events.jsonl.bak")
                    if (bak.exists()) bak.delete()
                    f.renameTo(bak)
                }
                writer = BufferedWriter(FileWriter(f, true), 8 * 1024)
            } catch (_: Exception) {
                writer = null
            }
        }
    }

    fun log(event: String, details: Map<String, Any?> = emptyMap(), nowUtcMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val w = writer ?: return
            try {
                val json = JSONObject().apply {
                    put("t", nowUtcMs)
                    put("event", event)
                    if (details.isNotEmpty()) {
                        val dObj = JSONObject()
                        details.forEach { (k, v) -> dObj.put(k, v ?: JSONObject.NULL) }
                        put("details", dObj)
                    }
                }
                w.write(json.toString())
                w.newLine()
                w.flush()
            } catch (_: Exception) {}
        }
    }
}
