package online.hatala.whereami.tracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import online.hatala.whereami.OsmMapMatcher
import online.hatala.whereami.TelemetryLogger
import online.hatala.whereami.TripDatabaseHelper
import online.hatala.whereami.TripManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background orchestration of post-trip road snapping for finished CAR trips.
 * Runs only while online and while no trip is recording (so live street lookups keep the OSRM rate budget),
 * processes a few trips per run, and is retried on later triggers (trip stop, app foreground, debounced).
 */
object PostTripMatcher {
    private const val MAX_TRIPS_PER_RUN = 2
    private const val MIN_RUN_INTERVAL_MS = 10 * 60_000L
    private const val RETRY_AFTER_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var lastRunMs = 0L

    fun schedule(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunMs < MIN_RUN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                lastRunMs = System.currentTimeMillis()
                if (!isOnline(appContext) || isRecording(appContext)) return@launch
                runPending(TripDatabaseHelper(appContext), OsmMapMatcher, { !isRecording(appContext) && isOnline(appContext) })
            } catch (e: Exception) {
                TelemetryLogger.log("OSRM", "post-trip match run failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private fun isRecording(context: Context): Boolean = TripManager.getInstance(context).activeTrip.value != null

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal fun runPending(db: TripDatabaseHelper, api: RouteMatchApi, shouldContinue: () -> Boolean) {
        val ids = db.pendingMatchTripIds(MAX_TRIPS_PER_RUN, System.currentTimeMillis(), RETRY_AFTER_MS)
        for (id in ids) {
            if (!shouldContinue()) return
            val points = RouteMatching.selectPoints(db.getTrackPointRows(id))
            val now = System.currentTimeMillis()
            when (val outcome = RouteMatching.matchTrack(points, api, shouldContinue)) {
                is TripMatchOutcome.Matched -> {
                    db.saveMatchResult(id, TripDatabaseHelper.MATCH_DONE, RouteMatching.geometryToJson(outcome.geometry), true, now)
                    TelemetryLogger.log("OSRM", "trip $id matched (${(outcome.matchedChunkFraction * 100).toInt()}% chunks)")
                }
                is TripMatchOutcome.Rejected ->
                    db.saveMatchResult(id, TripDatabaseHelper.MATCH_REJECTED, null, true, now)
                is TripMatchOutcome.Retry -> {
                    db.saveMatchResult(id, TripDatabaseHelper.MATCH_PENDING, null, false, now)
                    return // network trouble: stop this run, later trigger retries
                }
            }
        }
    }
}
