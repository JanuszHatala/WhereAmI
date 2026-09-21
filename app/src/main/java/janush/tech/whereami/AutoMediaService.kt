package janush.tech.whereami

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class AutoMediaService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:AutoMediaWakeLock")?.apply {
            acquire(6 * 60 * 60 * 1000L) // 6h safe timeout
        }

        mediaSession = MediaSessionCompat(this, "AutoMediaService").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                    .build()
            )
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        locationManager = LocationManager.getInstance(this)

        AppStateManager.getInstance(this).setAutoMediaActive(true)

        startForegroundService()
        startTracking()
    }

    private fun startForegroundService() {
        val channelId = "whereiam_media_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Android Auto Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android Auto Tracking")
            .setContentText("Broadcasting location to Android Auto")
            .setSmallIcon(R.drawable.ic_stat_location)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "AutoMediaService startForeground failed: ${e.message}")
        }
    }

    private fun startTracking() {
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val langStr = prefs.getString("display_language", DisplayLanguage.EN.name)
        val lang = try { DisplayLanguage.valueOf(langStr ?: DisplayLanguage.EN.name) } catch (_: Exception) { DisplayLanguage.EN }
        serviceScope.launch {
            locationManager.getLocationUpdates(lang).collectLatest { locationData ->
                updateMediaMetadata(locationData)
            }
        }
    }

    private var lastTitle: String = ""
    private var lastArtist: String = ""

    private fun updateMediaMetadata(data: LocationData) {
        val speedMs = data.speedMs ?: 0f
        val speedKmh = speedMs * 3.6f
        val speedStr = String.format(Locale.getDefault(), "%.1f km/h", speedKmh)

        val paceStr = if (speedKmh > 1.0f) {
            val paceMin = (60f / speedKmh).toInt()
            val paceSec = ((60f / speedKmh - paceMin) * 60).toInt()
            String.format(Locale.getDefault(), "%d:%02d/km", paceMin, paceSec)
        } else {
            "-/km"
        }

        val primaryPlace = data.primaryPlace
        val country = primaryPlace?.country?.takeIf { it != "Unknown Country" } ?: ""

        // Line 1: [City] • [Street / RoadRef]
        val title = if (primaryPlace != null && primaryPlace.city != "Unknown City") {
            val streetOrRef = listOfNotNull(
                primaryPlace.street?.takeIf { it.isNotBlank() },
                primaryPlace.roadRef?.takeIf { it.isNotBlank() }
            ).joinToString(" / ")

            if (streetOrRef.isNotEmpty()) {
                "${primaryPlace.city} • $streetOrRef"
            } else {
                primaryPlace.city
            }
        } else if (data.error != null) {
            data.error
        } else {
            if (lastTitle.isNotEmpty()) lastTitle else "Searching..."
        }

        // Line 2: [Speed] • [Hierarchy] • [Country] (Pace omitted in car)
        val hierarchy = if (primaryPlace != null) LocationManager.formatHierarchy(primaryPlace) else ""
        val artistParts = mutableListOf<String>()
        artistParts.add(speedStr)
        if (hierarchy.isNotEmpty()) {
            artistParts.add(hierarchy)
        }
        if (country.isNotEmpty()) {
            artistParts.add(country)
        }
        val artist = artistParts.joinToString(" • ")

        // Avoid pushing identical metadata (anti-flicker)
        if (title == lastTitle && artist == lastArtist) {
            return
        }
        lastTitle = title
        lastArtist = artist

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Where Am I")
            .build()

        mediaSession.setMetadata(metadata)

        // Populate Android Auto Queue / Playlist so driver can open queue drawer
        updateMediaQueue(primaryPlace)
    }

    private var lastQueueKey: String = ""
    private var lastQueueUpdateTime: Long = 0L

    private fun updateMediaQueue(place: PlaceInfo?) {
        if (place == null || place.city == "Unknown City") return

        val now = System.currentTimeMillis()
        val activeTrip = TripManager.getInstance(this).activeTrip.value
        val visitedCount = activeTrip?.placesVisited?.size ?: 0

        // Strip building numbers from street for queue stability (keeps list from jumping on every house passed)
        val baseStreet = place.street?.replace(Regex("\\s+\\d+[a-zA-Z]?(-\\d+)?"), "") ?: ""
        val queueKey = "${place.city}_${baseStreet}_${place.roadRef}_${place.gmina}_${place.powiat}_${place.voivodeship}_${place.country}_$visitedCount"

        // Rebuild queue only when locality / major road changes, or at least 45 seconds have passed
        if (queueKey == lastQueueKey && (now - lastQueueUpdateTime < 45_000L)) {
            return
        }
        lastQueueKey = queueKey
        lastQueueUpdateTime = now

        val queue = mutableListOf<MediaSessionCompat.QueueItem>()
        var queueId = 1L

        fun addQueueItem(title: String, subtitle: String) {
            val desc = android.support.v4.media.MediaDescriptionCompat.Builder()
                .setMediaId("item_${queueId}")
                .setTitle(title)
                .setSubtitle(subtitle)
                .build()
            queue.add(MediaSessionCompat.QueueItem(desc, queueId++))
        }

        // Locality
        addQueueItem("Locality", place.city)

        // Street / Road
        if (!place.street.isNullOrBlank() || !place.roadRef.isNullOrBlank()) {
            val road = listOfNotNull(place.street, place.roadRef).joinToString(" • ")
            addQueueItem("Street / Road", road)
        }

        // Hierarchy elements
        if (!place.gmina.isNullOrBlank()) {
            addQueueItem("Municipality (Gmina)", place.gmina)
        }
        if (!place.powiat.isNullOrBlank()) {
            addQueueItem("District (Powiat)", place.powiat)
        }
        if (place.voivodeship.isNotBlank() && place.voivodeship != "Unknown Region") {
            addQueueItem("Province / Region", place.voivodeship)
        }
        if (place.country.isNotBlank() && place.country != "Unknown Country") {
            addQueueItem("Country", place.country)
        }

        // Active trip places visited if any
        if (activeTrip != null && activeTrip.placesVisited.isNotEmpty()) {
            activeTrip.placesVisited.forEachIndexed { idx, vp ->
                addQueueItem("Visited #${idx + 1}", "${vp.placeName} (${vp.hierarchySubtitle})")
            }
        }

        mediaSession.setQueue(queue)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("lat", 0f).toDouble()
        val lng = prefs.getFloat("lng", 0f).toDouble()
        val lastPlace = locationManager.resolveMultiLanguageData(lat, lng).pl

        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        fun addBrowserItem(id: String, title: String, subtitle: String) {
            val desc = android.support.v4.media.MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build()
            items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }

        if (lastPlace.city != "Unknown City") {
            addBrowserItem("item_loc", "Locality: ${lastPlace.city}", LocationManager.formatHierarchy(lastPlace))
            if (!lastPlace.street.isNullOrBlank() || !lastPlace.roadRef.isNullOrBlank()) {
                addBrowserItem("item_road", "Road", listOfNotNull(lastPlace.street, lastPlace.roadRef).joinToString(" "))
            }
            addBrowserItem("item_country", "Country: ${lastPlace.country}", lastPlace.voivodeship)
        }

        result.sendResult(items)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppStateManager.getInstance(this).setAutoMediaActive(false)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        serviceJob.cancel()
        mediaSession.isActive = false
        mediaSession.release()
    }
}
