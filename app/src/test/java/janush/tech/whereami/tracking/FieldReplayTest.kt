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
        println(
            "REPLAY fixes=${fixes.size} decisions=$decisions moving=$moving headingConfident=" +
                "${"%.0f".format(100.0 * confident / moving.coerceAtLeast(1))}% " +
                "headingErr p50=${"%.1f".format(pct(0.5))} p90=${"%.1f".format(pct(0.9))} p97=${"%.1f".format(pct(0.97))} " +
                "smoothed=${smoothed.size} stored=${stored.size} spacing p50=${"%.2f".format(spacing.getOrNull(spacing.size / 2) ?: 0.0)}"
        )
        val delay = System.getenv("WAI_REPLAY_DELIVERY_MS")?.toLongOrNull() ?: 300L
        println("REPLAY render ${LiveRenderSim(profile, deliveryDelayMs = delay).run(fixes, LiveRenderSim::curved)}")
        if (System.getenv("WAI_REPLAY_COMPARE") != null) {
            val legacy: (RenderPipeline) -> Unit = { it.slowOnSpeedMps = 0.0; it.slowOffSpeedMps = 0.0 }
            println("REPLAY legacy ${LiveRenderSim(profile, legacy, pathArrow = false, deliveryDelayMs = delay).run(fixes)}")
            println("REPLAY straight ${LiveRenderSim(profile, deliveryDelayMs = delay).run(fixes)}")
        }
    }
}
