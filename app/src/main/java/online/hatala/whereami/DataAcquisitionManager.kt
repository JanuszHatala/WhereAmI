package online.hatala.whereami

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class DataAcquisitionStatus {
    HEALTHY,           // All online services responding normally
    CACHED_ONLY,       // Network geocoding failed or unavailable; relying on local spatial cache
    RATE_LIMITED_429,  // OSM Nominatim rate limit (HTTP 429); cooldown active
    OFFLINE            // No internet connection available
}

data class DataAcquisitionHealth(
    val status: DataAcquisitionStatus = DataAcquisitionStatus.HEALTHY,
    val detailMessage: String? = null,
    val lastUpdatedElapsedMs: Long = 0L,
    val cooldownRemainingSec: Int = 0
)

object DataAcquisitionManager {
    private val _health = MutableStateFlow(DataAcquisitionHealth())
    val health: StateFlow<DataAcquisitionHealth> = _health.asStateFlow()

    fun reportSuccess() {
        if (_health.value.status != DataAcquisitionStatus.HEALTHY) {
            _health.value = DataAcquisitionHealth(
                status = DataAcquisitionStatus.HEALTHY,
                detailMessage = null,
                lastUpdatedElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
        }
    }

    fun reportRateLimited(cooldownSec: Int = 30) {
        _health.value = DataAcquisitionHealth(
            status = DataAcquisitionStatus.RATE_LIMITED_429,
            detailMessage = "OSM Rate Limited (HTTP 429)",
            lastUpdatedElapsedMs = android.os.SystemClock.elapsedRealtime(),
            cooldownRemainingSec = cooldownSec
        )
    }

    fun reportError(error: Throwable?) {
        val status = when (error) {
            is UnknownHostException -> DataAcquisitionStatus.OFFLINE
            is SocketTimeoutException -> DataAcquisitionStatus.CACHED_ONLY
            else -> DataAcquisitionStatus.CACHED_ONLY
        }
        val msg = when (status) {
            DataAcquisitionStatus.OFFLINE -> "No Internet Connection"
            else -> "Network Error • Using Cache"
        }
        _health.value = DataAcquisitionHealth(
            status = status,
            detailMessage = msg,
            lastUpdatedElapsedMs = android.os.SystemClock.elapsedRealtime()
        )
    }

    fun reportCachedOnly(reason: String? = null) {
        if (_health.value.status != DataAcquisitionStatus.RATE_LIMITED_429) {
            _health.value = DataAcquisitionHealth(
                status = DataAcquisitionStatus.CACHED_ONLY,
                detailMessage = reason ?: "Offline Spatial Cache",
                lastUpdatedElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
        }
    }
}
