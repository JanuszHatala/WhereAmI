package janush.tech.whereami.tracking

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Per-session JSONL recorder of raw fixes, engine decisions and filter state.
 *
 * Exists because the Android logcat ring buffer loses a drive within ~30 minutes. Files live in
 * `filesDir/diagnostics/` (pulled by `pull_device_data.sh`) and can be replayed through
 * [TrackReplay] in unit tests to reproduce a field issue deterministically.
 *
 * Disk cost is ~250 B per fix (about 0.9 MB per hour at 1 Hz); only the last [MAX_FILES] are kept and
 * a file stops growing at [MAX_FILE_BYTES].
 */
class TrackDiagnosticsRecorder(private val dir: File) : TrackingDiagnosticsSink {

    private var writer: BufferedWriter? = null
    private var bytes = 0L
    private var lastFlushMs = 0L
    var currentFile: File? = null
        private set

    @Synchronized
    fun startSession(label: String, nowUtcMs: Long = System.currentTimeMillis()) {
        close()
        try {
            dir.mkdirs()
            val f = File(dir, "track_${nowUtcMs}_${label.replace(Regex("[^A-Za-z0-9]"), "")}.jsonl")
            writer = BufferedWriter(FileWriter(f, true), 32 * 1024)
            currentFile = f
            bytes = 0L
            pruneOld(f)
        } catch (_: Exception) {
            writer = null
        }
    }

    val isActive: Boolean @Synchronized get() = writer != null

    @Synchronized
    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    @Synchronized
    fun flush() {
        try { writer?.flush() } catch (_: Exception) {}
    }

    @Volatile
    var gyroRateForNextFix: Double? = null

    /** Compass heading (true north) at the next fix, when the compass is running (foreground, on foot). */
    @Volatile
    var compassForNextFix: Double? = null

    override fun onFix(fix: RawFix, decision: FixDecision, state: TrackState?) {
        writeLine(formatFixLine(fix, decision, state, gyroRateForNextFix, compassForNextFix))
    }

    @Synchronized
    fun note(tElapsedMs: Long, text: String) {
        writeLine("{\"k\":\"note\",\"te\":$tElapsedMs,\"m\":\"${text.replace("\"", "'")}\"}")
    }

    @Synchronized
    private fun writeLine(line: String) {
        val w = writer ?: return
        if (bytes > MAX_FILE_BYTES) return
        try {
            w.write(line)
            w.write('\n'.code)
            bytes += line.length + 1
            val now = System.nanoTime() / 1_000_000L
            if (now - lastFlushMs > 30_000L) {
                w.flush()
                lastFlushMs = now
            }
        } catch (_: Exception) {
        }
    }

    private fun pruneOld(keep: File) {
        val files = dir.listFiles { f -> f.name.startsWith("track_") && f.name.endsWith(".jsonl") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.name }.take(files.size - MAX_FILES).forEach { if (it != keep) it.delete() }
    }

    companion object {
        const val MAX_FILES = 10
        const val MAX_FILE_BYTES = 8L * 1024 * 1024

        fun formatFixLine(fix: RawFix, decision: FixDecision, state: TrackState?, gyro: Double?, compass: Double? = null): String {
            val sb = StringBuilder(256)
            sb.append("{\"k\":\"fix\",\"te\":").append(fix.tElapsedMs)
            sb.append(",\"tu\":").append(fix.tUtcMs)
            sb.append(String.format(Locale.US, ",\"lat\":%.7f,\"lng\":%.7f", fix.lat, fix.lng))
            fix.accuracyM?.let { sb.append(String.format(Locale.US, ",\"acc\":%.2f", it)) }
            fix.speedMps?.let { sb.append(String.format(Locale.US, ",\"spd\":%.3f", it)) }
            fix.speedAccMps?.let { sb.append(String.format(Locale.US, ",\"sacc\":%.3f", it)) }
            fix.bearingDeg?.let { sb.append(String.format(Locale.US, ",\"brg\":%.2f", it)) }
            fix.bearingAccDeg?.let { sb.append(String.format(Locale.US, ",\"bacc\":%.2f", it)) }
            fix.altitudeM?.let { sb.append(String.format(Locale.US, ",\"alt\":%.1f", it)) }
            gyro?.let { sb.append(String.format(Locale.US, ",\"gy\":%.4f", it)) }
            compass?.let { sb.append(String.format(Locale.US, ",\"cp\":%.1f", it)) }
            sb.append(",\"d\":\"").append(decision.name).append('"')
            if (state != null) {
                sb.append(
                    String.format(
                        Locale.US,
                        ",\"s\":{\"lat\":%.7f,\"lng\":%.7f,\"v\":%.3f,\"h\":%.2f,\"w\":%.3f,\"ps\":%.2f,\"hs\":%.1f,\"hc\":%d,\"st\":%d,\"rs\":%d}",
                        state.lat, state.lng, state.speedMps, state.headingDeg, state.yawRateDegPerS,
                        state.posStdM, state.headingStdDeg,
                        if (state.headingConfident) 1 else 0,
                        if (state.isStationary) 1 else 0,
                        state.rejectStreak
                    )
                )
            }
            sb.append('}')
            return sb.toString()
        }
    }
}

/** Parses recorder output back into [RawFix] sequences for deterministic replay. */
object TrackReplay {
    private fun num(line: String, key: String): Double? {
        val m = Regex("\"$key\":(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)").find(line) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }

    data class ReplayFix(val fix: RawFix, val gyroYawRateRadS: Double?)

    fun parse(lines: Sequence<String>): List<ReplayFix> = lines
        .filter { it.contains("\"k\":\"fix\"") }
        .mapNotNull { l ->
            val te = num(l, "te")?.toLong() ?: return@mapNotNull null
            val tu = num(l, "tu")?.toLong() ?: te
            val lat = num(l, "lat") ?: return@mapNotNull null
            val lng = num(l, "lng") ?: return@mapNotNull null
            ReplayFix(
                RawFix(
                    tElapsedMs = te, tUtcMs = tu, lat = lat, lng = lng,
                    accuracyM = num(l, "acc")?.toFloat(),
                    speedMps = num(l, "spd")?.toFloat(),
                    speedAccMps = num(l, "sacc")?.toFloat(),
                    bearingDeg = num(l, "brg")?.toFloat(),
                    bearingAccDeg = num(l, "bacc")?.toFloat(),
                    altitudeM = num(l, "alt")
                ),
                num(l, "gy")
            )
        }
        .toList()
}
