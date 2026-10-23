package janush.tech.whereami.tracking

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Replays a diagnostics JSONL pulled from a device (`files/diagnostics/track_*.jsonl`) through the engine
 * and prints field metrics. Skipped unless `WAI_REPLAY_JSONL` points at a file and `WAI_REPLAY_PROFILE`
 * (default WALKING) names a [TrackingProfile].
 */
class FieldReplayTest {

    private fun num(line: String, key: String): Double? =
        Regex("\"$key\":(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun parse(file: File): List<RawFix> = file.readLines()
        .filter { it.startsWith("{\"k\":\"fix\"") }
        .mapNotNull { l ->
            val head = l.substringBefore(",\"s\":")
            RawFix(
                tElapsedMs = num(head, "te")?.toLong() ?: return@mapNotNull null,
                tUtcMs = num(head, "tu")?.toLong() ?: 0L,
                lat = num(head, "lat") ?: return@mapNotNull null,
                lng = num(head, "lng") ?: return@mapNotNull null,
                accuracyM = num(head, "acc")?.toFloat(),
                speedMps = num(head, "spd")?.toFloat(),
                speedAccMps = num(head, "sacc")?.toFloat(),
                bearingDeg = num(head, "brg")?.toFloat(),
                bearingAccDeg = num(head, "bacc")?.toFloat(),
                altitudeM = num(head, "alt")
            )
        }

    @Test
    fun replay() {
        val path = System.getenv("WAI_REPLAY_JSONL")
        assumeTrue(path != null && File(path).isFile)
        val profile = when (System.getenv("WAI_REPLAY_PROFILE") ?: "WALKING") {
            "CAR" -> TrackingProfile.CAR
            "CYCLING" -> TrackingProfile.CYCLING
            "MTB" -> TrackingProfile.MTB
            "RUNNING" -> TrackingProfile.RUNNING
            "HIKING" -> TrackingProfile.HIKING
            else -> TrackingProfile.WALKING
        }
        val fixes = parse(File(path!!))
        val engine = TrackingEngine(profile)
        val sampler = AdaptiveTrackSampler()
        val states = ArrayList<TrackState>()
        val smoothed = ArrayList<SmoothedPoint>()
        val stored = ArrayList<SmoothedPoint>()
        val decisions = HashMap<FixDecision, Int>()
        for (f in fixes) {
            val r = engine.ingest(f)
            decisions[r.decision] = (decisions[r.decision] ?: 0) + 1
            r.state?.let { states.add(it) }
            for (p in r.finalized) { smoothed.add(p); stored.addAll(sampler.offer(p)) }
        }
        for (p in engine.flush()) { smoothed.add(p); stored.addAll(sampler.offer(p)) }
        stored.addAll(sampler.drain())

        // Heading vs. the course of the next/previous 3 s of smoothed motion (ground truth proxy).
        val frame = LocalFrame(fixes.first().lat, fixes.first().lng)
        val errs = ArrayList<Double>()
        var moving = 0
        var confident = 0
        for (s in states) {
            if (s.isStationary || s.speedMps < 0.7) continue
            moving++
            if (s.headingConfident) confident++
            val a = smoothed.lastOrNull { it.tElapsedMs <= s.tElapsedMs - 3_000 } ?: continue
            val b = smoothed.firstOrNull { it.tElapsedMs >= s.tElapsedMs + 3_000 } ?: continue
            val dE = frame.east(b.lng) - frame.east(a.lng)
            val dN = frame.north(b.lat) - frame.north(a.lat)
            if (hypot(dE, dN) < 4.0) continue
            val course = wrapDeg360(Math.toDegrees(atan2(dE, dN)))
            errs.add(abs(shortestDeltaDeg(s.headingDeg, course)))
        }
        errs.sort()
        fun pct(p: Double) = if (errs.isEmpty()) Double.NaN else errs[((errs.size - 1) * p).toInt()]
        val spacing = stored.zipWithNext { a, b -> AdaptiveTrackSampler.distanceM(a, b) }.sorted()
        val committed = ArrayList<SmoothedPoint>()
        var lastUtc = -1L
        for (p in stored) {
            val last = committed.lastOrNull()
            if (last != null) {
                val prevUtc = lastUtc.takeIf { it > 0L } ?: p.tUtcMs
                val twoPt = TrackSpikeGuard.isSpikeStep(last.lat, last.lng, prevUtc, p.lat, p.lng, p.tUtcMs, p.speedMps, p.isStationary)
                val prev2 = if (committed.size >= 2) committed[committed.size - 2] else null
                val threePt = prev2 != null && TrackSpikeGuard.isReversalSpike(prev2.lat, prev2.lng, last.lat, last.lng, prevUtc, p.lat, p.lng, p.tUtcMs)
                if (twoPt || threePt) continue
                if (prev2 != null && TrackSpikeGuard.isLateralVSpike(prev2.lat, prev2.lng, last.lat, last.lng, p.lat, p.lng)) {
                    committed.removeAt(committed.lastIndex)
                }
            }
            committed.add(p)
            lastUtc = p.tUtcMs
        }
        val maxCommittedChord = committed.zipWithNext { a, b -> AdaptiveTrackSampler.distanceM(a, b) }.maxOrNull() ?: 0.0
        println(
            "REPLAY fixes=${fixes.size} decisions=$decisions moving=$moving headingConfident=" +
                "${"%.0f".format(100.0 * confident / moving.coerceAtLeast(1))}% " +
                "headingErr p50=${"%.1f".format(pct(0.5))} p90=${"%.1f".format(pct(0.9))} p97=${"%.1f".format(pct(0.97))} " +
                "smoothed=${smoothed.size} stored=${stored.size} committed=${committed.size} maxChord=${"%.1f".format(maxCommittedChord)}m spacing p50=${"%.2f".format(spacing.getOrNull(spacing.size / 2) ?: 0.0)}"
        )
        println("REPLAY position ${positionMetrics(fixes, profile)}")
        val delay = System.getenv("WAI_REPLAY_DELIVERY_MS")?.toLongOrNull() ?: 300L
        val dump = System.getenv("WAI_REPLAY_DUMP")?.let { File(it).printWriter() }
        println("REPLAY render ${LiveRenderSim(profile, deliveryDelayMs = delay, dumpPose = dump?.let { w -> { t, la, ln -> w.println("$t,$la,$ln") } }).run(fixes, LiveRenderSim::curved)}")
        dump?.close()
        if (System.getenv("WAI_REPLAY_COMPARE") != null) {
            val legacy: (RenderPipeline) -> Unit = { it.slowOnSpeedMps = 0.0; it.slowOffSpeedMps = 0.0 }
            println("REPLAY legacy ${LiveRenderSim(profile, legacy, pathArrow = false, deliveryDelayMs = delay).run(fixes)}")
            println("REPLAY nojoinblend ${LiveRenderSim(profile, deliveryDelayMs = delay, blendJoin = false).run(fixes, LiveRenderSim::curved)}")
            println("REPLAY straight ${LiveRenderSim(profile, deliveryDelayMs = delay).run(fixes)}")
            if (profile.maxSpeedMps > RenderPipeline.ON_FOOT_MAX_SPEED_MPS) {
                for (lat in listOf(0.0, 600.0, 1_300.0)) {
                    println("REPLAY latency${lat.toInt()} ${LiveRenderSim(profile, { it.minLatencyMs = lat }, deliveryDelayMs = delay).run(fixes, LiveRenderSim::curved)}")
                }
            }
        }
    }

    /**
     * Engine position against the provider fixes (what the user perceives as "the line is beside the road"):
     *  - offRaw: filter state vs. its own fix while moving; episodes: runs of >= 3 fixes over 10 m.
     *  - brake/accel: along-track offset (+ = ahead of the fix) while decelerating / accelerating > 1 m/s^2.
     *  - stopDrift: how far the cursor moves while stationary; anchorErr: anchored position vs. the median
     *    fix of the stop (the jump seen when driving off).
     */
    private fun positionMetrics(fixes: List<RawFix>, profile: TrackingProfile): String {
        val engine = TrackingEngine(profile)
        val frame = LocalFrame(fixes.first().lat, fixes.first().lng)
        val off = ArrayList<Double>()
        val brake = ArrayList<Double>()
        val accel = ArrayList<Double>()
        val drift = ArrayList<Double>()
        val anchorErr = ArrayList<Double>()
        var episodes = 0
        var run = 0
        val printEpisodes = System.getenv("WAI_REPLAY_EPISODES") != null
        var prevFix: RawFix? = null
        var stopStart: TrackState? = null
        var stopLast: TrackState? = null
        val stopE = ArrayList<Double>()
        val stopN = ArrayList<Double>()
        fun closeStop() {
            val a = stopStart; val b = stopLast
            if (a != null && b != null && stopE.size >= 3) {
                drift.add(hypot(frame.east(b.lng) - frame.east(a.lng), frame.north(b.lat) - frame.north(a.lat)))
                anchorErr.add(hypot(frame.east(b.lng) - stopE.sorted()[stopE.size / 2], frame.north(b.lat) - stopN.sorted()[stopN.size / 2]))
            }
            stopStart = null; stopLast = null; stopE.clear(); stopN.clear()
        }
        for (f in fixes) {
            val s = engine.ingest(f).state ?: continue
            val dE = frame.east(s.lng) - frame.east(f.lng)
            val dN = frame.north(s.lat) - frame.north(f.lat)
            if (s.isStationary) {
                if (stopStart == null) stopStart = s
                stopLast = s
                stopE.add(frame.east(f.lng)); stopN.add(frame.north(f.lat))
            } else {
                closeStop()
                val d = hypot(dE, dN)
                off.add(d)
                if (d > 10.0) {
                    if (++run == 3) {
                        episodes++
                        if (printEpisodes) println("REPLAY episode tUtc=${f.tUtcMs} off=%.1f acc=${f.accuracyM}".format(d))
                    }
                } else run = 0
                val p = prevFix
                val v0 = p?.speedMps; val v1 = f.speedMps
                if (p != null && v0 != null && v1 != null) {
                    val dt = (f.tElapsedMs - p.tElapsedMs) / 1000.0
                    if (dt in 0.5..1.6) {
                        val a = (v1 - v0) / dt
                        val h = Math.toRadians(s.headingDeg)
                        val along = dE * kotlin.math.sin(h) + dN * kotlin.math.cos(h)
                        if (a < -1.0) brake.add(along) else if (a > 1.0) accel.add(along)
                    }
                }
            }
            prevFix = f
        }
        closeStop()
        fun pct(l: List<Double>, q: Double) = l.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * q).toInt()] }
        return "offRaw p50=%.1f p90=%.1f p99=%.1f episodes>10m=%d brake p50=%+.1f accel p50=%+.1f stopDrift p90=%.1f max=%.1f anchorErr p90=%.1f max=%.1f stops=%d"
            .format(pct(off, .5), pct(off, .9), pct(off, .99), episodes, pct(brake, .5), pct(accel, .5),
                pct(drift, .9), drift.maxOrNull() ?: 0.0, pct(anchorErr, .9), anchorErr.maxOrNull() ?: 0.0, drift.size)
    }
}
