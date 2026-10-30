package online.hatala.whereami.tracking

/**
 * Recent filter states in time order, for the render pipeline.
 *
 * The UI receives fixes through a conflated flow and does not recompose while the screen is off, so a
 * screen-off batch (or a burst of fixes during a busy frame) reached [RenderPipeline] as its last state
 * only and the cursor interpolated one long chord across the gap. The map instead reads every state
 * newer than the last one it consumed.
 */
class TrackStateLog(private val capacity: Int = 120) {
    private val states = ArrayDeque<TrackState>()

    @Synchronized
    fun add(s: TrackState) {
        if (s.isReset) states.clear()
        else if (states.isNotEmpty() && s.tElapsedMs <= states.last().tElapsedMs) return
        states.addLast(s)
        while (states.size > capacity) states.removeFirst()
    }

    /** States strictly newer than [afterMs] (elapsedRealtime), oldest first. */
    @Synchronized
    fun since(afterMs: Long): List<TrackState> {
        if (states.isEmpty() || states.last().tElapsedMs <= afterMs) return emptyList()
        return states.filter { it.tElapsedMs > afterMs }
    }

    @Synchronized
    fun clear() = states.clear()
}
