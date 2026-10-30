package online.hatala.whereami.tracking

import kotlin.math.abs

/**
 * Ring buffer of compass headings (true north, degrees) on the `elapsedRealtime` clock, so the arrow can be
 * sampled at the render time (the cursor runs behind the newest fix) and diagnostics can log the heading at
 * a fix time.
 */
class CompassTracker(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val headings = DoubleArray(capacity)
    private var head = 0
    private var count = 0

    @Synchronized
    fun clear() { head = 0; count = 0 }

    @Synchronized
    fun onSample(tMs: Long, headingDeg: Double) {
        if (count > 0 && tMs <= times[(head - 1 + capacity) % capacity]) return
        times[head] = tMs
        headings[head] = wrapDeg360(headingDeg)
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun latestSampleMs(): Long? = if (count == 0) null else times[(head - 1 + capacity) % capacity]

    /**
     * Heading at [tMs] by circular interpolation between the neighbouring samples. A time after the newest
     * sample is held for at most [maxHoldMs]; a time before the oldest sample gives null.
     */
    @Synchronized
    fun headingAt(tMs: Long, maxHoldMs: Long = 400L): Double? {
        if (count == 0) return null
        val newest = (head - 1 + capacity) % capacity
        if (tMs >= times[newest]) return if (tMs - times[newest] <= maxHoldMs) headings[newest] else null
        var i = count - 1
        while (i > 0) {
            val idx = (head - count + i + capacity) % capacity
            if (times[idx] <= tMs) break
            i--
        }
        val i0 = (head - count + i + capacity) % capacity
        if (times[i0] > tMs) return null
        val i1 = (i0 + 1) % capacity
        val span = (times[i1] - times[i0]).toDouble()
        if (span <= 0.0 || abs(span) > 2_000.0) return headings[i0]
        val u = (tMs - times[i0]) / span
        return wrapDeg360(headings[i0] + shortestDeltaDeg(headings[i0], headings[i1]) * u)
    }
}
