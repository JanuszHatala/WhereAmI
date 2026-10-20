package janush.tech.whereami

/**
 * Changes or removes a location request without losing the fixes Fused is still holding in a batch.
 *
 * Re-requesting (or removing) a callback whose request had `maxUpdateDelayMillis` discards the pending
 * batch: trip 234 lost 10-17 s of driving at every screen-on, and the stored track bridged each hole with
 * a straight chord. The batch is flushed first and the change is applied once it has been delivered. A
 * newer change supersedes a pending one (only the latest is applied).
 */
class BatchedRequestSequencer {
    private var generation = 0L

    /**
     * @param wasBatched the request currently registered batches fixes
     * @param flush flushes the batch and calls its argument once the fixes were delivered (or on failure)
     * @param apply the request change to make
     */
    fun run(wasBatched: Boolean, flush: (onDone: () -> Unit) -> Unit, apply: () -> Unit) {
        val gen = synchronized(this) { ++generation }
        if (!wasBatched) {
            apply()
            return
        }
        flush { if (synchronized(this) { gen == generation }) apply() }
    }
}
