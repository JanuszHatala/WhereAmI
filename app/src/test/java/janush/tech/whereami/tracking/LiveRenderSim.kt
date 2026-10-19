package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Offline model of the live map: engine + [RenderPipeline] at 60 Hz + [PencilTrail] + committed track,
 * wired like OsmMapView. Measures what the user sees: arrow vs. drawn line, line re-shaping when smoothed
 * vertices replace the pencil, and corner sharpness of the drawn line.
 */
class LiveRenderSim(
    private val profile: TrackingProfile,
    private val configure: (RenderPipeline) -> Unit = {},
    private val pathArrow: Boolean = true,
    private val deliveryDelayMs: Long = 300L,
    private val blendJoin: Boolean = true
) {

    data class Metrics(
        val arrowVsLineP50: Double,
        val arrowVsLineP90: Double,
        val arrowVsTruthP50: Double,
        val arrowVsTruthP90: Double,
        val reshapeP50: Double,
        val reshapeP90: Double,
        val pencilTurnP90: Double,
        val displayTurnP90: Double,
        /** Sharpest bend within 8 m behind the cursor (the committed/pencil join), p90 over time. */
        val tailKinkP90: Double = Double.NaN,
        /** Share of moving arrow samples pointing more than 120 deg away from the smoothed course. */
        val arrowReversePct: Double = Double.NaN
    ) {
        override fun toString() =
            "arrowVsLine p50=%.1f p90=%.1f arrowVsTruth p50=%.1f p90=%.1f reshape p50=%.2fm p90=%.2fm vertexTurn pencil p90=%.0f display p90=%.0f tailKink p90=%.0f arrowReverse=%.2f%%"
                .format(arrowVsLineP50, arrowVsLineP90, arrowVsTruthP50, arrowVsTruthP90, reshapeP50, reshapeP90, pencilTurnP90, displayTurnP90, tailKinkP90, arrowReversePct)
    }

    private class P(val t: Long, val e: Double, val n: Double)

    fun run(fixes: List<RawFix>, display: (List<P2>) -> List<P2> = { it }): Metrics {
        val frame = LocalFrame(fixes.first().lat, fixes.first().lng)
        val engine = TrackingEngine(profile)
        val sampler = AdaptiveTrackSampler()
        val rp = RenderPipeline(profile)
        rp.movingOnSpeedMps = 0.5
        configure(rp)
        val pencil = PencilTrail()
        val motion = MotionCourse()
        val committed = ArrayList<P>()
        val smoothed = ArrayList<SmoothedPoint>()
        var committedThrough = -1L
        var bearing: Double? = null
        val arrowLine = ArrayList<Double>()
        val tailKinks = ArrayList<Double>()
        val arrowSamples = ArrayList<Pair<Long, Double>>()
        val pruned = ArrayList<P>()

        fun commit(p: SmoothedPoint) {
            committed.add(P(p.tElapsedMs, frame.east(p.lng), frame.north(p.lat)))
            committedThrough = p.tElapsedMs
        }

        var i = 0
        var now = fixes.first().tElapsedMs
        val end = fixes.last().tElapsedMs + 1_000
        var lastSample = 0L
        var lastFresh = Long.MIN_VALUE / 2
        while (now <= end) {
            while (i < fixes.size && fixes[i].tElapsedMs + deliveryDelayMs <= now) {
                val r = engine.ingest(fixes[i])
                r.state?.let { rp.onState(it) }
                for (p in r.finalized) { smoothed.add(p); sampler.offer(p).forEach(::commit) }
                i++
            }
            val pose = rp.frame(now)
            if (pose != null) {
                if (pose.headingFresh) lastFresh = now
                val motionDeg = motion.update(now, pose.lat, pose.lng)
                val live = when {
                    pathArrow && pose.isMoving && pose.pathHeadingDeg != null -> pose.pathHeadingDeg
                    pose.headingFresh -> pose.headingDeg
                    pose.isMoving ->
                        if (profile.maxSpeedMps <= RenderPipeline.ON_FOOT_MAX_SPEED_MPS) motionDeg
                        else MotionCourse.forVehicle(motionDeg, pose.headingDeg.takeIf { pose.headingConfident }, now - lastFresh)
                    else -> null
                }
                if (live != null) bearing = live
                if (!pose.isStationary) pencil.add(pose.renderTimeMs, pose.lat, pose.lng, pose.speedMps)
                var drop = 0
                while (drop < pencil.size && pencil.timeAt(drop) <= committedThrough) {
                    pruned.add(P(pencil.timeAt(drop), frame.east(pencil.lngAt(drop)), frame.north(pencil.latAt(drop))))
                    drop++
                }
                pencil.pruneThrough(committedThrough)
                val tip = P2(frame.east(pose.lng), frame.north(pose.lat))
                if (now - lastSample >= 250 && pose.isMoving && bearing != null) {
                    lastSample = now
                    val raw = ArrayList<P2>(committed.size + pencil.size + 1)
                    committed.forEach { raw.add(P2(it.e, it.n)) }
                    val last = committed.lastOrNull()
                    val bl = if (blendJoin && last != null) {
                        pencil.blendedFrom(committedThrough, frame.lat(last.n), frame.lng(last.e), pose.lat, pose.lng)
                    } else null
                    if (bl != null) {
                        for (q in 0 until pencil.size) raw.add(P2(frame.east(bl.second[q]), frame.north(bl.first[q])))
                    } else {
                        pencil.forEach { la, ln -> raw.add(P2(frame.east(ln), frame.north(la))) }
                    }
                    raw.add(tip)
                    val line = display(raw)
                    tangentAtEnd(line, 1.0)?.let { arrowLine.add(abs(shortestDeltaDeg(bearing!!, it))) }
                    tailKinks.add(tailKink(line, 8.0))
                    arrowSamples.add(pose.renderTimeMs to bearing!!)
                }
            }
            now += 16
        }

        val truth = ArrayList<Double>()
        for ((t, b) in arrowSamples) {
            val a = smoothed.lastOrNull { it.tElapsedMs <= t - 3_000 } ?: continue
            val c = smoothed.firstOrNull { it.tElapsedMs >= t + 3_000 } ?: continue
            val dE = frame.east(c.lng) - frame.east(a.lng)
            val dN = frame.north(c.lat) - frame.north(a.lat)
            if (hypot(dE, dN) < 4.0) continue
            truth.add(abs(shortestDeltaDeg(b, wrapDeg360(Math.toDegrees(atan2(dE, dN))))))
        }

        val stored = committed.map { P2(it.e, it.n) }
        val reshape = pruned.map { distToPolyline(P2(it.e, it.n), stored) }
        val allPencil = ArrayList<P2>()
        pruned.forEach { allPencil.add(P2(it.e, it.n)) }
        val pencilTurnsAll = vertexTurns(allPencil)
        val displayTurns = vertexTurns(display(stored))

        fun pct(l: List<Double>, q: Double) = l.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * q).toInt()] }
        return Metrics(
            pct(arrowLine, .5), pct(arrowLine, .9), pct(truth, .5), pct(truth, .9),
            pct(reshape, .5), pct(reshape, .9), pct(pencilTurnsAll, .9), pct(displayTurns, .9), pct(tailKinks, .9),
            if (truth.isEmpty()) Double.NaN else 100.0 * truth.count { it > 120.0 } / truth.size
        )
    }

    companion object {
        /** The production display curve ([CurvedPolyline]) applied to a planar line. */
        fun curved(l: List<P2>): List<P2> {
            if (l.size < 3) return l
            val out = arrayListOf(l[0])
            // P2 is metres; feed it through the lat/lng API on a 1 deg = 1 m frame near the equator.
            val k = LocalFrame.METERS_PER_DEG_LAT
            CurvedPolyline.densify(l.size, 0, l.size - 1, { l[it].y / k }, { l[it].x / k }) { la, ln ->
                out.add(P2(ln * k, la * k))
            }
            return out
        }

        /** Direction of the last [arcM] metres of [line] (from the point that far back to the tip). */
        fun tangentAtEnd(line: List<P2>, arcM: Double): Double? {
            if (line.size < 2) return null
            val tip = line.last()
            var acc = 0.0
            for (j in line.size - 2 downTo 0) {
                acc += hypot(line[j + 1].x - line[j].x, line[j + 1].y - line[j].y)
                if (acc >= arcM) {
                    return wrapDeg360(Math.toDegrees(atan2(tip.x - line[j].x, tip.y - line[j].y)))
                }
            }
            return null
        }

        fun distToPolyline(p: P2, l: List<P2>): Double {
            if (l.isEmpty()) return 0.0
            if (l.size == 1) return hypot(p.x - l[0].x, p.y - l[0].y)
            var best = Double.MAX_VALUE
            for (j in 0 until l.size - 1) {
                val a = l[j]; val b = l[j + 1]
                val bx = b.x - a.x; val by = b.y - a.y
                val len2 = bx * bx + by * by
                val t = if (len2 < 1e-12) 0.0 else (((p.x - a.x) * bx + (p.y - a.y) * by) / len2).coerceIn(0.0, 1.0)
                val d = hypot(p.x - a.x - t * bx, p.y - a.y - t * by)
                if (d < best) best = d
            }
            return best
        }

        /** Largest direction change at a drawn vertex within [arcM] metres behind the tip. */
        fun tailKink(line: List<P2>, arcM: Double): Double {
            var acc = 0.0
            var from = 0
            for (j in line.size - 2 downTo 0) {
                acc += hypot(line[j + 1].x - line[j].x, line[j + 1].y - line[j].y)
                if (acc >= arcM) { from = j; break }
            }
            val turns = vertexTurns(line.subList(from, line.size))
            return turns.maxOrNull() ?: 0.0
        }

        /** Direction change (deg) at each drawn vertex: what makes a line look angular on screen. */
        fun vertexTurns(l: List<P2>): List<Double> {
            val r = l.filterIndexed { j, p -> j == 0 || hypot(p.x - l[j - 1].x, p.y - l[j - 1].y) > 0.05 }
            val out = ArrayList<Double>()
            for (j in 1 until r.size - 1) {
                val ax = r[j].x - r[j - 1].x; val ay = r[j].y - r[j - 1].y
                val bx = r[j + 1].x - r[j].x; val by = r[j + 1].y - r[j].y
                val den = hypot(ax, ay) * hypot(bx, by)
                if (den < 1e-9) continue
                out.add(Math.toDegrees(acos(((ax * bx + ay * by) / den).coerceIn(-1.0, 1.0))))
            }
            return out
        }
    }
}

data class P2(val x: Double, val y: Double)
