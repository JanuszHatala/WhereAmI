package janush.tech.whereami

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton manager coordinating bulk & single-trip Elevation Correction with
 * pause, resume, and stop controls, persistent progress notifications, and
 * saved places elevation correction.
 */
object ElevationCorrectionManager {

    private const val NOTIF_CHANNEL_ELEVATION = "elevation_correction_channel"
    private const val NOTIF_ELEVATION_ID = 9182

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile
    private var isPausedInternal = false

    @Volatile
    private var isRunningInternal = false

    private val _progress = MutableStateFlow(ElevationCorrectionProgress())
    val progress: StateFlow<ElevationCorrectionProgress> = _progress.asStateFlow()

    private var appContext: Context? = null

    fun pause() {
        if (!isRunningInternal || isPausedInternal) return
        isPausedInternal = true
        _progress.value = _progress.value.copy(isPaused = true)
        appContext?.let { updateNotification(it, _progress.value) }
    }

    fun resume() {
        if (!isRunningInternal || !isPausedInternal) return
        isPausedInternal = false
        _progress.value = _progress.value.copy(isPaused = false)
        appContext?.let { updateNotification(it, _progress.value) }
    }

    fun stop() {
        isRunningInternal = false
        isPausedInternal = false
        job?.cancel()
        job = null
        _progress.value = ElevationCorrectionProgress(isRunning = false, isPaused = false, isDone = false)
        appContext?.let { dismissNotification(it) }
    }

    fun startSingleTrip(
        context: Context,
        tripId: Long,
        mode: OverrideMode,
        notifyCompletion: Boolean = true,
        onTripUpdated: (() -> Unit)? = null
    ): Boolean {
        if (isRunningInternal) return false
        val appCtx = context.applicationContext
        appContext = appCtx
        isRunningInternal = true
        isPausedInternal = false

        createNotificationChannel(appCtx)
        _progress.value = ElevationCorrectionProgress(
            isRunning = true, phase = ElevationCorrectionPhase.TRIPS, totalTrips = 1, singleTripId = tripId
        )

        job = scope.launch {
            val dbHelper = TripDatabaseHelper(appCtx)
            val trip = dbHelper.getTripsByIds(listOf(tripId)).firstOrNull()
            if (trip == null) {
                stop()
                return@launch
            }

            _progress.value = ElevationCorrectionProgress(
                isRunning = true,
                isPaused = false,
                phase = ElevationCorrectionPhase.TRIPS,
                processedTrips = 0,
                totalTrips = 1,
                processedPoints = 0,
                totalPoints = trip.points.size,
                singleTripId = tripId,
                statusMessage = "Processing trip: ${trip.title.ifBlank { "Trip #$tripId" }}"
            )
            updateNotification(appCtx, _progress.value)

            val success = ElevationCorrectionHelper.correctTrip(
                trip = trip,
                mode = mode,
                db = dbHelper,
                isCancelled = { !isRunningInternal }
            ) { done, total ->
                _progress.value = _progress.value.copy(
                    processedPoints = done,
                    totalPoints = total
                )
                updateNotification(appCtx, _progress.value)
            }

            if (success && isRunningInternal) {
                onTripUpdated?.invoke()
                _progress.value = ElevationCorrectionProgress(
                    isRunning = false,
                    isDone = true,
                    phase = ElevationCorrectionPhase.COMPLETED,
                    processedTrips = 1,
                    totalTrips = 1,
                    singleTripId = tripId,
                    statusMessage = "Elevation correction complete for 1 trip."
                )
                if (notifyCompletion) showCompletionNotification(appCtx, "Elevation correction complete for 1 trip.")
                else dismissNotification(appCtx)
            } else {
                _progress.value = ElevationCorrectionProgress(singleTripId = tripId)
                dismissNotification(appCtx)
            }
            isRunningInternal = false
            isPausedInternal = false
        }
        return true
    }

    fun startAll(
        context: Context,
        mode: OverrideMode,
        onPlacesCompleted: (() -> Unit)? = null,
        onAllCompleted: (() -> Unit)? = null
    ) {
        if (isRunningInternal) return
        val appCtx = context.applicationContext
        appContext = appCtx
        isRunningInternal = true
        isPausedInternal = false

        createNotificationChannel(appCtx)

        job = scope.launch {
            val dbHelper = TripDatabaseHelper(appCtx)
            val allTrips = dbHelper.getAllTrips().filter { it.points.isNotEmpty() }
            val allSavedPlaces = dbHelper.getAllSavedPlaces()

            val totalTripsCount = allTrips.size
            val totalPlacesCount = allSavedPlaces.size

            _progress.value = ElevationCorrectionProgress(
                isRunning = true,
                isPaused = false,
                phase = if (allSavedPlaces.isNotEmpty()) ElevationCorrectionPhase.SAVED_PLACES else ElevationCorrectionPhase.TRIPS,
                totalTrips = totalTripsCount,
                totalPlaces = totalPlacesCount,
                statusMessage = "Starting elevation correction..."
            )
            updateNotification(appCtx, _progress.value)

            // Phase 1: Saved Places
            if (allSavedPlaces.isNotEmpty() && isRunningInternal) {
                while (isPausedInternal && isRunningInternal) {
                    delay(250L)
                }
                if (!isRunningInternal) {
                    dismissNotification(appCtx)
                    return@launch
                }

                _progress.value = _progress.value.copy(
                    phase = ElevationCorrectionPhase.SAVED_PLACES,
                    processedTrips = 0,
                    processedPlaces = 0,
                    totalPlaces = totalPlacesCount,
                    statusMessage = "Processing Saved Places (0/$totalPlacesCount)..."
                )
                updateNotification(appCtx, _progress.value)

                ElevationCorrectionHelper.correctSavedPlaces(
                    places = allSavedPlaces,
                    mode = mode,
                    db = dbHelper,
                    isCancelled = { !isRunningInternal }
                ) { done, total ->
                    while (isPausedInternal && isRunningInternal) {
                        Thread.sleep(200)
                    }
                    _progress.value = _progress.value.copy(
                        processedPlaces = done,
                        totalPlaces = total,
                        statusMessage = "Processing Saved Places ($done/$total)"
                    )
                    updateNotification(appCtx, _progress.value)
                }

                // Immediately trigger callback so UI reloads saved places without waiting for trips
                onPlacesCompleted?.invoke()
            }

            // Phase 2: Trips
            for ((idx, trip) in allTrips.withIndex()) {
                while (isPausedInternal && isRunningInternal) {
                    delay(250L)
                }
                if (!isRunningInternal) {
                    dismissNotification(appCtx)
                    return@launch
                }

                _progress.value = _progress.value.copy(
                    phase = ElevationCorrectionPhase.TRIPS,
                    processedTrips = idx,
                    totalPoints = trip.points.size,
                    processedPoints = 0,
                    statusMessage = "Processing trip ${idx + 1}/$totalTripsCount"
                )
                updateNotification(appCtx, _progress.value)

                ElevationCorrectionHelper.correctTrip(
                    trip = trip,
                    mode = mode,
                    db = dbHelper,
                    isCancelled = { !isRunningInternal }
                ) { done, total ->
                    while (isPausedInternal && isRunningInternal) {
                        Thread.sleep(200)
                    }
                    _progress.value = _progress.value.copy(
                        processedPoints = done,
                        totalPoints = total
                    )
                    updateNotification(appCtx, _progress.value)
                }
            }

            if (isRunningInternal) {
                onAllCompleted?.invoke()
                val summary = "Complete: $totalPlacesCount saved places and $totalTripsCount trips updated."
                _progress.value = ElevationCorrectionProgress(
                    isRunning = false,
                    isDone = true,
                    phase = ElevationCorrectionPhase.COMPLETED,
                    processedTrips = totalTripsCount,
                    totalTrips = totalTripsCount,
                    processedPlaces = totalPlacesCount,
                    totalPlaces = totalPlacesCount,
                    statusMessage = summary
                )
                showCompletionNotification(appCtx, summary)
            } else {
                dismissNotification(appCtx)
            }

            isRunningInternal = false
            isPausedInternal = false
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ELEVATION,
                "Elevation Data Correction",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and controls for background elevation corrections."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(context: Context, state: ElevationCorrectionProgress) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_ELEVATION_SETTINGS
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS_ELEVATION, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            context,
            100,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = if (state.isPaused) {
            val i = Intent(context, ElevationActionReceiver::class.java).apply {
                action = ElevationActionReceiver.ACTION_RESUME
            }
            PendingIntent.getBroadcast(context, 101, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            val i = Intent(context, ElevationActionReceiver::class.java).apply {
                action = ElevationActionReceiver.ACTION_PAUSE
            }
            PendingIntent.getBroadcast(context, 102, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(context, ElevationActionReceiver::class.java).apply {
            action = ElevationActionReceiver.ACTION_STOP
        }
        val pendingStop = PendingIntent.getBroadcast(
            context,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            state.isPaused -> "Elevation Correction (Paused)"
            state.phase == ElevationCorrectionPhase.SAVED_PLACES -> "Elevation Correction • Saved Places"
            else -> "Elevation Correction • Trips"
        }

        val contentText = when (state.phase) {
            ElevationCorrectionPhase.SAVED_PLACES ->
                "Saved Places: ${state.processedPlaces}/${state.totalPlaces}"
            else -> {
                val tripPart = "Trip ${state.processedTrips + 1}/${state.totalTrips.coerceAtLeast(1)}"
                val ptPart = if (state.totalPoints > 0) " (${state.processedPoints}/${state.totalPoints} pts)" else ""
                val placePart = if (state.totalPlaces > 0) " • Places: ${state.totalPlaces}" else ""
                "$tripPart$ptPart$placePart"
            }
        }

        val totalMax = if (state.phase == ElevationCorrectionPhase.SAVED_PLACES) {
            state.totalPlaces.coerceAtLeast(1)
        } else {
            (state.totalTrips * 100).coerceAtLeast(1)
        }
        val currentProgress = if (state.phase == ElevationCorrectionPhase.SAVED_PLACES) {
            state.processedPlaces
        } else {
            val base = state.processedTrips * 100
            val fraction = if (state.totalPoints > 0) (state.processedPoints * 100) / state.totalPoints else 0
            base + fraction
        }

        val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ELEVATION)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(pendingContent)
            .setProgress(totalMax, currentProgress, false)
            .setOngoing(!state.isPaused)
            .setOnlyAlertOnce(true)
            .addAction(0, if (state.isPaused) "Resume" else "Pause", pauseResumeIntent)
            .addAction(0, "Stop", pendingStop)

        manager.notify(NOTIF_ELEVATION_ID, builder.build())
    }

    private fun showCompletionNotification(context: Context, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_ELEVATION_SETTINGS
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS_ELEVATION, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            context,
            104,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ELEVATION)
            .setContentTitle("Elevation Correction Complete")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(pendingContent)
            .setAutoCancel(true)
            .setOngoing(false)

        manager.notify(NOTIF_ELEVATION_ID, builder.build())
    }

    fun dismissNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIF_ELEVATION_ID)
    }
}
