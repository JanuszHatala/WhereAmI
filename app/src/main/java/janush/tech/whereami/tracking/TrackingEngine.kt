package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Single source of truth for position, speed and heading.
 *
 * Replaces the former chain GpsFilterEngine -> hybrid speed Kalman -> bearing EMA ->
 * PositionStabilizer. One physically motivated model (CTRV EKF) with:
 *  - accuracy gate (profile based, relaxed after long silence)
 *  - innovation (Mahalanobis) gate plus implied-speed hard cap
 *  - consistent-rejection reset (fixes the recovery deadlock of distance-from-first-anomaly checks)
 *  - zero-velocity updates (ZUPT) replacing anchor / jitter-dampener heuristics
 *  - Doppler speed, course (weighted by speed and bearing accuracy) and gyro yaw-rate measurements
 *  - fixed-lag RTS smoothing for the recorded track
 *
 * Not thread-safe by design: callers serialise access (LocationManager calls it from the main looper).
 */
class TrackingEngine(
    profile: TrackingProfile = TrackingProfile.CAR,
    lagMs: Long = DEFAULT_LAG_MS,
    private val diagnostics: TrackingDiagnosticsSink? = null
) {
    companion object {
        const val DEFAULT_LAG_MS = 3_000L

        /** Chi-square (2 dof) gate, ~99.97%. */
        const val POSITION_GATE_D2 = 16.0

        /** After this long without an accepted fix the filter is re-seeded instead of propagated. */
        const val MAX_PROPAGATION_GAP_MS = 8_000L

        /** After this much silence the accuracy gate is relaxed (deep canopy / canyon). */
        const val SILENCE_RELAX_MS = 45_000L

        const val ANCHOR_AFTER_MS = 10_000L
        const val ANCHOR_AFTER_FIXES = 4
        /** Share of the fix variance re-opened when the target comes to rest. */
        const val STOP_ENTRY_VARIANCE_FRACTION = 0.5
        const val STATIONARY_SPEED_MPS = 0.35
        const val REJECTS_FOR_RESET = 3
        const val REBASE_DISTANCE_M = 6_000.0
        const val HEADING_CONFIDENT_STD_DEG = 30.0

        /** Kinematic envelopes ordered by top speed (escalation path for a mislabelled activity). */
        val ENVELOPE_LADDER = listOf(
            TrackingProfile.WALKING, TrackingProfile.HIKING, TrackingProfile.RUNNING,
            TrackingProfile.MTB, TrackingProfile.CYCLING, TrackingProfile.CAR
        )
        const val ENVELOPE_FRACTION = 0.85
        const val ENVELOPE_ESCALATE_FIXES = 3
        const val ENVELOPE_RELAX_MS = 60_000L
        const val ENVELOPE_MAX_SPEED_ACC_MPS = 2.0

        /**
         * Extra course uncertainty near the heading-observability speed. The provider's bearing accuracy
         * already grows at low speed, so this only guards the last few tenths of a m/s.
         */
        const val COURSE_LOW_SPEED_INFLATION = 0.5

        /** A bearing biased by d deg (vs. the fix displacement) is weighted as if its error were this x d. */
        const val BIAS_SIGMA_FACTOR = 2.0
    }

    /** The selected activity's tuning: the prior, not a cage (see [activeProfile]). */
    var profile: TrackingProfile = profile
        set(value) {
            field = value
            activeProfile = value
            fastFixCount = 0
            slowSinceMs = -1L
        }

    /**
     * Tuning actually applied. Starts at [profile] and escalates to a faster envelope when the measured
     * motion is physically impossible for it (e.g. "Walking" selected while driving), so a wrong profile
     * degrades nothing; it falls back after sustained motion that fits [profile] again.
     */
    var activeProfile: TrackingProfile = profile
        private set(value) {
            field = value
            ekf.profile = value
        }
    private var fastFixCount = 0
    private var slowSinceMs = -1L

    private val ekf = KinematicEkf(profile)
    private val smoother = FixedLagSmoother(lagMs)
    private var frame: LocalFrame? = null

    private var lastAcceptedElapsedMs = 0L
    private var lastAcceptedFix: RawFix? = null
    private var rejectedChain = ArrayList<RawFix>()
    private var rejectStreak = 0
    private var stationarySinceMs = -1L
    private var lowSpeedCount = 0
    private var courseRejectCount = 0
    private var headingObserved = false
    private var stationary = false
    private val courseConsistency = CourseConsistency()

    var lastState: TrackState? = null
        private set

    val isInitialized: Boolean get() = ekf.initialized

    /** Smoothed estimate of the not-yet-finalised window (for the live polyline tail). */
    fun provisionalTrack(): List<SmoothedPoint> = frame?.let { smoother.provisional(it) } ?: emptyList()

    /** Emit all smoothed points still inside the lag window (call at trip stop / pause). */
    fun flush(): List<SmoothedPoint> {
        val f = frame ?: return emptyList()
        return smoother.flush(f)
    }

    /** Forget everything (new session). Returns smoothed points that were still pending. */
    fun reset(): List<SmoothedPoint> {
        val pending = flush()
        smoother.clear()
        frame = null
        lastAcceptedElapsedMs = 0L
        lastAcceptedFix = null
        rejectedChain.clear()
        rejectStreak = 0
        lowSpeedCount = 0
        stationarySinceMs = -1L
        courseRejectCount = 0
        headingObserved = false
        stationary = false
        courseConsistency.clear()
        lastState = null
        activeProfile = profile
        fastFixCount = 0
        slowSinceMs = -1L
        ekf.reinitializeEmpty()
        return pending
    }

    /**
     * @param gyroYawRateRadS mean gyro-derived turn rate (clockwise positive) over the interval since the
     *        previous fix, or null if the gyro was not running / not trusted.
     * @param externalStationary accelerometer based "device is physically still" flag.
     */
    fun ingest(
        fix: RawFix,
        gyroYawRateRadS: Double? = null,
        externalStationary: Boolean = false
    ): EngineResult {
        // ── Stale / duplicate ────────────────────────────────────────────────────
        if (ekf.initialized && fix.tElapsedMs <= lastAcceptedElapsedMs) {
            return reject(fix, FixDecision.REJECTED_STALE, emptyList())
        }

        // ── Accuracy gate ────────────────────────────────────────────────────────
        val acc = fix.accuracyM
        if (acc != null && acc > activeProfile.maxAccuracyM) {
            val silence = if (lastAcceptedElapsedMs > 0L) fix.tElapsedMs - lastAcceptedElapsedMs else 0L
            val relaxed = silence > SILENCE_RELAX_MS && acc <= activeProfile.maxAccuracyM * 2f
            if (!relaxed) return reject(fix, FixDecision.REJECTED_ACCURACY, emptyList())
        }

        updateEnvelope(fix)

        // ── First fix or propagation gap: (re)seed ───────────────────────────────
        if (!ekf.initialized) {
            return seed(fix, emptyList(), FixDecision.ACCEPTED, externalStationary)
        }
        if (fix.tElapsedMs - lastAcceptedElapsedMs > MAX_PROPAGATION_GAP_MS && !continuesStationary(fix)) {
            val pending = flush()
            return seed(fix, pending, FixDecision.ACCEPTED_AFTER_RESET, externalStationary)
        }

        rebaseIfNeeded()
        val fr = frame!!

        // Snapshot so a rejected fix leaves no trace (keeps the RTS transition chain consistent).
        val snapX = ekf.copyState()
        val snapP = ekf.copyCov()
        val snapT = ekf.tMs

        val zE = fr.east(fix.lng)
        val zN = fr.north(fix.lat)
        val sigma = max((acc ?: 8f).toDouble(), 2.0)
        val dtSec = (fix.tElapsedMs - lastAcceptedElapsedMs) / 1000.0
        val prev = lastAcceptedFix!!
        val dist = hypot(zE - fr.east(prev.lng), zN - fr.north(prev.lat))
        val impliedSpeed = dist / max(dtSec, 0.001)

        val dopplerSpeed = fix.speedMps?.toDouble()
        val lowSpeed = if (dopplerSpeed != null) {
            dopplerSpeed < STATIONARY_SPEED_MPS
        } else {
            dist < 1.5 * sigma && impliedSpeed < 0.5
        }

        // Releasing a stationary anchor: the filter was nearly certain the target was at rest, so
        // re-open position uncertainty *before* gating or the first steps would be rejected.
        val wasStationary = stationary
        if (wasStationary && !lowSpeed) {
            ekf.stationaryMode = false
            ekf.inflatePositionVariance(max(sigma, 3.0).let { it * it })
        }

        val f = ekf.predict(fix.tElapsedMs)!!
        val xPred = ekf.copyState()
        val pPred = ekf.copyCov()

        val d2 = ekf.positionInnovationD2(zE, zN, sigma)
        val speedAnomaly = impliedSpeed > activeProfile.maxSpeedMps * 1.2 && (dist > 15.0 || dtSec < 0.8)
        val isTurningVehicle = activeProfile.maxSpeedMps > 15.0 && impliedSpeed <= activeProfile.maxSpeedMps &&
            (acc ?: 8f) <= 15f && kotlin.math.abs(xPred[KinematicEkf.YAW]) > 0.05
        val gateD2 = if (isTurningVehicle) 25.0 else POSITION_GATE_D2

        if (speedAnomaly || d2 > gateD2) {
            ekf.restore(snapX, snapP, snapT)
            ekf.stationaryMode = wasStationary
            val decision = if (speedAnomaly) FixDecision.REJECTED_IMPLIED_SPEED else FixDecision.REJECTED_INNOVATION
            // Consistent-rejection reset: several mutually agreeing "outliers" are the new truth
            // (tunnel exit, boarding transport, long signal loss).
            if (registerRejectedAndCheckReset(fix, sigma)) {
                if (speedAnomaly) escalateEnvelope(chainSpeedMps())
                val pending = flush()
                return seed(fix, pending, FixDecision.ACCEPTED_AFTER_RESET, externalStationary)
            }
            return reject(fix, decision, emptyList())
        }

        // ── Accepted: measurement updates ────────────────────────────────────────
        rejectedChain.clear()
        rejectStreak = 0

        lowSpeedCount = if (lowSpeed) lowSpeedCount + 1 else 0
        val wasStat = stationary
        stationary = lowSpeedCount >= 2 || (lowSpeedCount >= 1 && externalStationary)
        ekf.stationaryMode = stationary
        if (stationary && !wasStat) {
            stationarySinceMs = fix.tElapsedMs
            // The braking estimate is overconfident about where the vehicle came to rest. Re-open the
            // position variance once so the stop converges to the mean of its fixes within seconds, instead
            // of creeping for the whole convergence window and jumping when driving off.
            val extra = STOP_ENTRY_VARIANCE_FRACTION * sigma * sigma
            ekf.inflatePositionVariance(extra)
            pPred[KinematicEkf.EAST][KinematicEkf.EAST] += extra
            pPred[KinematicEkf.NORTH][KinematicEkf.NORTH] += extra
        }
        if (!stationary) stationarySinceMs = -1L

        // Parked: after a short convergence period the position is anchored. Further fixes only carry
        // multipath wander (which would creep the marker); spike/innovation gating above still applies.
        val anchored = stationary && stationarySinceMs >= 0L &&
            fix.tElapsedMs - stationarySinceMs >= ANCHOR_AFTER_MS && lowSpeedCount >= ANCHOR_AFTER_FIXES
        if (!anchored) ekf.updatePosition(zE, zN, sigma)

        if (stationary) {
            ekf.applyZupt()
            courseConsistency.clear()
        } else {
            courseConsistency.add(fix.tElapsedMs, fix.lat, fix.lng, fix.bearingDeg?.toDouble(), fix.speedMps?.toDouble(), sigma)
            if (dopplerSpeed != null) {
                val sAcc = fix.speedAccMps?.toDouble()?.takeIf { it > 0.0 }
                val sigV = if (sAcc != null) max(sAcc, 0.2) else 0.6
                ekf.updateScalar(KinematicEkf.SPEED, dopplerSpeed, sigV * sigV, angular = false)
            }
            applyCourse(fix, dopplerSpeed)
            if (gyroYawRateRadS != null && ekf.x[KinematicEkf.SPEED] >= activeProfile.minHeadingSpeedMps &&
                abs(gyroYawRateRadS) <= maxTrustedGyroRate()
            ) {
                ekf.updateScalar(KinematicEkf.YAW, gyroYawRateRadS, 0.04 * 0.04, angular = false)
            }
        }

        lastAcceptedElapsedMs = fix.tElapsedMs
        lastAcceptedFix = fix

        val state = buildState(fix, isReset = false)
        val step = FixedLagSmoother.Step(
            tElapsedMs = fix.tElapsedMs,
            tUtcMs = fix.tUtcMs,
            altitudeM = fix.altitudeM,
            accuracyM = (acc ?: 8f),
            isStationary = stationary,
            xf = ekf.copyState(),
            pf = ekf.copyCov(),
            xPred = xPred,
            pPred = pPred,
            f = f,
            rawLat = fix.lat,
            rawLng = fix.lng
        )
        val finalized = smoother.add(step, fr)
        lastState = state
        diagnostics?.onFix(fix, FixDecision.ACCEPTED, state)
        return EngineResult(FixDecision.ACCEPTED, state, finalized)
    }

    /**
     * A parked device gets sparse fixes (the fused provider throttles when still). A gap is then not lost
     * tracking: re-seeding would jump the anchor to one noisy indoor fix, so the stationary state carries on
     * when the new fix is slow and inside the anchor's uncertainty.
     */
    private fun continuesStationary(fix: RawFix): Boolean {
        if (!stationary) return false
        val v = fix.speedMps
        if (v != null && v >= STATIONARY_SPEED_MPS) return false
        val fr = frame ?: return false
        val sigma = max((fix.accuracyM ?: 8f).toDouble(), 2.0)
        val d = hypot(fr.east(fix.lng) - ekf.x[KinematicEkf.EAST], fr.north(fix.lat) - ekf.x[KinematicEkf.NORTH])
        return d <= max(2.5 * sigma, 15.0)
    }

    private fun updateEnvelope(fix: RawFix) {
        val v = fix.speedMps?.toDouble() ?: return
        val sAcc = fix.speedAccMps?.toDouble()
        if (sAcc != null && sAcc > ENVELOPE_MAX_SPEED_ACC_MPS) return
        if (v > activeProfile.maxSpeedMps * ENVELOPE_FRACTION) {
            if (++fastFixCount >= ENVELOPE_ESCALATE_FIXES) {
                escalateEnvelope(v)
                fastFixCount = 0
            }
        } else {
            fastFixCount = 0
        }
        if (activeProfile === profile) return
        if (v <= profile.maxSpeedMps * ENVELOPE_FRACTION) {
            if (slowSinceMs < 0L) slowSinceMs = fix.tElapsedMs
            else if (fix.tElapsedMs - slowSinceMs >= ENVELOPE_RELAX_MS) {
                activeProfile = profile
                slowSinceMs = -1L
            }
        } else {
            slowSinceMs = -1L
        }
    }

    private fun escalateEnvelope(speedMps: Double) {
        val next = ENVELOPE_LADDER.firstOrNull {
            it.maxSpeedMps > activeProfile.maxSpeedMps && it.maxSpeedMps * ENVELOPE_FRACTION > speedMps
        } ?: ENVELOPE_LADDER.last()
        if (next.maxSpeedMps > activeProfile.maxSpeedMps) {
            activeProfile = next
            slowSinceMs = -1L
        }
    }

    private fun chainSpeedMps(): Double {
        val a = rejectedChain.firstOrNull() ?: return 0.0
        val b = rejectedChain.last()
        val fr = frame ?: return 0.0
        val dt = (b.tElapsedMs - a.tElapsedMs) / 1000.0
        if (dt <= 0.0) return 0.0
        return hypot(fr.east(b.lng) - fr.east(a.lng), fr.north(b.lat) - fr.north(a.lat)) / dt
    }

    private fun maxTrustedGyroRate(): Double = when (activeProfile.name) {
        "CAR", "CYCLING", "MTB" -> 1.2 // beyond this the phone is being waved around, not steered
        else -> 3.0
    }

    private fun applyCourse(fix: RawFix, dopplerSpeed: Double?) {
        val bearing = fix.bearingDeg ?: return
        val v = dopplerSpeed ?: ekf.x[KinematicEkf.SPEED]
        if (v < activeProfile.minHeadingSpeedMps) return
        val reportedDeg = (fix.bearingAccDeg?.toDouble()?.takeIf { it > 0.0 } ?: 8.0).coerceAtLeast(3.0)
        // A bearing that disagrees with the fixes' own displacement is less accurate than reported.
        val baseDeg = max(reportedDeg, BIAS_SIGMA_FACTOR * (courseConsistency.disagreementDeg ?: 0.0))
        val sigRad = Math.toRadians(baseDeg) * (1.0 + COURSE_LOW_SPEED_INFLATION * activeProfile.minHeadingSpeedMps / v)
        val z = Math.toRadians(bearing.toDouble())
        val (nu, s) = ekf.scalarInnovation(KinematicEkf.HEADING, z, sigRad * sigRad, angular = true)
        val gated = abs(nu) > Math.toRadians(60.0) && nu * nu > 16.0 * s
        if (gated) {
            courseRejectCount++
            if (courseRejectCount >= 2) {
                ekf.reseedHeading(z, sigRad * 2.0)
                courseRejectCount = 0
                headingObserved = true
            }
            return
        }
        courseRejectCount = 0
        ekf.updateScalar(KinematicEkf.HEADING, z, sigRad * sigRad, angular = true)
        headingObserved = true
    }

    private fun registerRejectedAndCheckReset(fix: RawFix, sigma: Double): Boolean {
        rejectStreak++
        val last = rejectedChain.lastOrNull()
        val fr = frame!!
        if (last != null) {
            val dt = max((fix.tElapsedMs - last.tElapsedMs) / 1000.0, 0.001)
            val d = hypot(fr.east(fix.lng) - fr.east(last.lng), fr.north(fix.lat) - fr.north(last.lat))
            val consistent = d <= activeProfile.maxSpeedMps * dt + 2.0 * sigma + (last.accuracyM ?: 8f) * 2.0
            if (!consistent) rejectedChain.clear()
        }
        rejectedChain.add(fix)
        if (rejectedChain.size > 8) rejectedChain.removeAt(0)
        val threshold = if (activeProfile.maxSpeedMps < 5.0) 5 else REJECTS_FOR_RESET
        return rejectedChain.size >= threshold
    }

    private fun reject(fix: RawFix, decision: FixDecision, finalized: List<SmoothedPoint>): EngineResult {
        if (decision == FixDecision.REJECTED_ACCURACY) rejectStreak++
        diagnostics?.onFix(fix, decision, lastState)
        return EngineResult(decision, null, finalized)
    }

    private fun seed(
        fix: RawFix,
        pending: List<SmoothedPoint>,
        decision: FixDecision,
        externalStationary: Boolean
    ): EngineResult {
        val prevHeading = if (ekf.initialized && headingObserved) ekf.x[KinematicEkf.HEADING] else null
        if (frame == null) frame = LocalFrame(fix.lat, fix.lng) else {
            // Keep the existing frame but make sure we stay within linearisation range.
            val fr = frame!!
            val e = fr.east(fix.lng)
            val n = fr.north(fix.lat)
            if (hypot(e, n) > REBASE_DISTANCE_M) {
                frame = LocalFrame(fix.lat, fix.lng)
                smoother.clear()
            }
        }
        val fr = frame!!
        val speed = fix.speedMps?.toDouble() ?: 0.0
        val acc = (fix.accuracyM ?: 8f).toDouble()
        val courseOk = fix.bearingDeg != null && speed >= activeProfile.minHeadingSpeedMps
        val heading: Double? = if (courseOk) Math.toRadians(fix.bearingDeg.toDouble()) else prevHeading
        ekf.init(
            east = fr.east(fix.lng),
            north = fr.north(fix.lat),
            speed = speed,
            headingRad = heading,
            yawRate = 0.0,
            posSigmaM = max(acc, 2.0),
            speedSigma = fix.speedAccMps?.toDouble()?.coerceAtLeast(0.3) ?: 1.0,
            tElapsedMs = fix.tElapsedMs
        )
        if (!courseOk && prevHeading != null) {
            // Heading carried over from before the gap is much less certain.
            ekf.p[KinematicEkf.HEADING][KinematicEkf.HEADING] = Math.toRadians(40.0).let { it * it }
        }
        headingObserved = courseOk || prevHeading != null
        courseRejectCount = 0
        rejectedChain.clear()
        rejectStreak = 0
        lowSpeedCount = if (speed < STATIONARY_SPEED_MPS && fix.speedMps != null) 1 else 0
        stationary = lowSpeedCount >= 1 && externalStationary
        stationarySinceMs = if (stationary) fix.tElapsedMs else -1L
        ekf.stationaryMode = stationary
        if (stationary) ekf.applyZupt()
        lastAcceptedElapsedMs = fix.tElapsedMs
        lastAcceptedFix = fix

        val state = buildState(fix, isReset = true)
        smoother.add(
            FixedLagSmoother.Step(
                tElapsedMs = fix.tElapsedMs,
                tUtcMs = fix.tUtcMs,
                altitudeM = fix.altitudeM,
                accuracyM = (fix.accuracyM ?: 8f),
                isStationary = stationary,
                xf = ekf.copyState(),
                pf = ekf.copyCov(),
                xPred = null,
                pPred = null,
                f = null,
                rawLat = fix.lat,
                rawLng = fix.lng
            ),
            fr
        )
        lastState = state
        diagnostics?.onFix(fix, decision, state)
        return EngineResult(decision, state, pending)
    }

    private fun rebaseIfNeeded() {
        val fr = frame ?: return
        val e = ekf.x[KinematicEkf.EAST]
        val n = ekf.x[KinematicEkf.NORTH]
        if (hypot(e, n) < REBASE_DISTANCE_M) return
        val newFrame = LocalFrame(fr.lat(n), fr.lng(e))
        ekf.shiftOrigin(e, n)
        smoother.shiftOrigin(e, n)
        frame = newFrame
    }

    private fun buildState(fix: RawFix, isReset: Boolean): TrackState {
        val fr = frame!!
        val headingStdDeg = Math.toDegrees(ekf.headingStdRad)
        return TrackState(
            tElapsedMs = fix.tElapsedMs,
            tUtcMs = fix.tUtcMs,
            lat = fr.lat(ekf.x[KinematicEkf.NORTH]),
            lng = fr.lng(ekf.x[KinematicEkf.EAST]),
            altitudeM = fix.altitudeM,
            speedMps = if (stationary) 0.0 else ekf.x[KinematicEkf.SPEED],
            headingDeg = wrapDeg360(Math.toDegrees(ekf.x[KinematicEkf.HEADING])),
            yawRateDegPerS = Math.toDegrees(ekf.x[KinematicEkf.YAW]),
            posStdM = ekf.posStdM,
            headingStdDeg = headingStdDeg,
            headingConfident = headingObserved && headingStdDeg < HEADING_CONFIDENT_STD_DEG,
            isStationary = stationary,
            rejectStreak = rejectStreak,
            isReset = isReset,
            accuracyM = fix.accuracyM,
            accelMps2 = if (stationary) 0.0 else ekf.x[KinematicEkf.ACCEL]
        )
    }
}

/** Optional diagnostics hook (implemented by TrackDiagnosticsRecorder). */
interface TrackingDiagnosticsSink {
    fun onFix(fix: RawFix, decision: FixDecision, state: TrackState?)
}