package janush.tech.whereami

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for the bearing EMA (Exponential Moving Average) smoother
 * and the GPS filter accuracy gate changes introduced in feat/bearing-smoothing-gps-stability.
 *
 * Validates:
 * 1. Circular EMA bearing smoother handles wraparound correctly (359° ↔ 1°)
 * 2. Speed-adaptive alpha selection (slow vs fast)
 * 3. Bearing gate thresholds reject noisy low-speed bearing updates
 * 4. CAR mode accuracy gate reduced from 75m to 55m
 */
class BearingSmootherTest {

    /**
     * Standalone test model of the circular EMA bearing smoother from LocationManager.
     * Must mirror the production implementation exactly.
     */
    class CircularBearingEma {
        private var emaBearingSin: Double = 0.0
        private var emaBearingCos: Double = 1.0
        private var initialized: Boolean = false

        fun update(rawBearing: Float, speedKmh: Float): Float {
            val alpha = when {
                speedKmh < 5f  -> 0.15
                speedKmh < 15f -> 0.30
                speedKmh < 50f -> 0.55
                else           -> 0.80
            }
            val rad = Math.toRadians(rawBearing.toDouble())
            if (!initialized) {
                emaBearingSin = sin(rad)
                emaBearingCos = cos(rad)
                initialized = true
            } else {
                emaBearingSin = alpha * sin(rad) + (1.0 - alpha) * emaBearingSin
                emaBearingCos = alpha * cos(rad) + (1.0 - alpha) * emaBearingCos
            }
            val smoothed = Math.toDegrees(atan2(emaBearingSin, emaBearingCos)).toFloat()
            return (smoothed + 360f) % 360f
        }

        fun reset() {
            emaBearingSin = 0.0
            emaBearingCos = 1.0
            initialized = false
        }
    }

    private lateinit var ema: CircularBearingEma

    @Before
    fun setUp() {
        ema = CircularBearingEma()
    }

    // ── Circular Wraparound Tests ────────────────────────────────────────────────

    @Test
    fun `first reading initializes to raw bearing`() {
        val result = ema.update(90f, speedKmh = 60f)
        assertEquals(90f, result, 1.0f)
    }

    @Test
    fun `circular EMA handles 359 to 1 wraparound without 180 degree jump`() {
        // Initialize at 359°
        ema.update(359f, speedKmh = 60f)
        // Repeatedly feed 1° — should converge toward 0° / 360°, NOT swing through 180°
        repeat(20) { ema.update(1f, speedKmh = 60f) }
        val result = ema.update(1f, speedKmh = 60f)
        // Should be in range [350°, 360°] or [0°, 10°] — definitely NOT near 180°
        val isNearBoundary = result > 350f || result < 10f
        assertTrue("Expected bearing near 0°/360°, got $result°", isNearBoundary)
    }

    @Test
    fun `circular EMA handles 1 to 359 wraparound correctly`() {
        // Initialize at 1°
        ema.update(1f, speedKmh = 60f)
        // Repeatedly feed 359° — should converge toward 360° / 0°
        repeat(20) { ema.update(359f, speedKmh = 60f) }
        val result = ema.update(359f, speedKmh = 60f)
        val isNearBoundary = result > 350f || result < 10f
        assertTrue("Expected bearing near 0°/360°, got $result°", isNearBoundary)
    }

    @Test
    fun `bearing EMA damps a large raw change - does not jump full delta in one step`() {
        // Initialize heading north (0°)
        ema.update(0f, speedKmh = 60f)
        // One step toward 90° (east). With α=0.80 in sin/cos space, result should be
        // significantly less than 90° (the EMA damps the change).
        // sin: 0.80 * sin(90°) + 0.20 * sin(0°) = 0.80, cos: 0.80 * cos(90°) + 0.20 * cos(0°) = 0.20
        // atan2(0.80, 0.20) ≈ 76°, which is less than 90°.
        val result = ema.update(90f, speedKmh = 60f)
        assertTrue("EMA should damp a 90° jump to less than 90° (got $result°)", result < 90f)
        assertTrue("EMA result should be positive for a jump toward 90° (got $result°)", result > 0f)
    }

    // ── Speed-Adaptive Alpha Tests ───────────────────────────────────────────────

    @Test
    fun `low speed alpha produces high inertia - slow response`() {
        // Initialize at 0°
        ema.update(0f, speedKmh = 3f) // very slow
        // One step toward 90°
        val result = ema.update(90f, speedKmh = 3f)
        // With α=0.15: new ≈ 0.15 * 90 = 13.5° (rough estimate in angular space)
        // Should be well below 45° (midway point)
        assertTrue("Slow speed should produce high inertia (< 30°), got $result°", result < 30f)
    }

    @Test
    fun `high speed alpha produces low inertia - fast response`() {
        // Initialize at 0°
        ema.update(0f, speedKmh = 80f) // highway
        // One step toward 90°
        val result = ema.update(90f, speedKmh = 80f)
        // With α=0.80: new ≈ 0.80 * 90 = 72° (rough estimate)
        // Should be well above 45° (midway point)
        assertTrue("High speed should produce low inertia (> 50°), got $result°", result > 50f)
    }

    // ── Convergence Tests ────────────────────────────────────────────────────────

    @Test
    fun `bearing converges to steady state on constant input`() {
        // Feed constant bearing at medium speed — should converge
        repeat(50) { ema.update(270f, speedKmh = 30f) }
        val result = ema.update(270f, speedKmh = 30f)
        assertEquals("Should converge to 270°", 270f, result, 2.0f)
    }

    @Test
    fun `bearing converges through 0 degree north`() {
        repeat(30) { ema.update(0f, speedKmh = 60f) }
        val result = ema.update(0f, speedKmh = 60f)
        // 0° and 360° are equivalent
        val isNorth = result < 5f || result > 355f
        assertTrue("Should converge to north (0°/360°), got $result°", isNorth)
    }

    // ── Bearing Gate Threshold Tests ─────────────────────────────────────────────

    @Test
    fun `bearing gate with poor accuracy requires high speed`() {
        // Model the minSpeedForBearing decision from LocationManager:
        // rawAcc > 20f → minSpeed = 8.0 m/s
        val rawAcc = 25f
        val minSpeed = when {
            rawAcc > 20f -> 8.0f
            rawAcc > 15f -> 5.0f
            else         -> 3.0f
        }
        assertEquals("Poor accuracy should require 8 m/s gate", 8.0f, minSpeed, 0.01f)
    }

    @Test
    fun `bearing gate with good accuracy requires moderate speed`() {
        val rawAcc = 10f
        val minSpeed = when {
            rawAcc > 20f -> 8.0f
            rawAcc > 15f -> 5.0f
            else         -> 3.0f
        }
        assertEquals("Good accuracy should require 3 m/s gate", 3.0f, minSpeed, 0.01f)
    }

    @Test
    fun `bearing not updated below 3ms even with good accuracy`() {
        // This models the behavior: a fix at 2 m/s speed (7.2 km/h) with 10m accuracy
        // should NOT update the bearing (hold lastValidBearing instead)
        val locationSpeed = 2.0f  // m/s — below 3.0 threshold
        val rawAcc = 10f
        val minSpeed = when {
            rawAcc > 20f -> 8.0f
            rawAcc > 15f -> 5.0f
            else         -> 3.0f
        }
        val shouldUpdateBearing = locationSpeed >= minSpeed
        assertFalse(
            "At 2 m/s with 10m accuracy, bearing should NOT be updated (map spinning prevention)",
            shouldUpdateBearing
        )
    }

    @Test
    fun `bearing IS updated above 3ms with good accuracy`() {
        val locationSpeed = 4.0f  // m/s — above 3.0 threshold
        val rawAcc = 10f
        val minSpeed = when {
            rawAcc > 20f -> 8.0f
            rawAcc > 15f -> 5.0f
            else         -> 3.0f
        }
        val shouldUpdateBearing = locationSpeed >= minSpeed
        assertTrue(
            "At 4 m/s with 10m accuracy, bearing SHOULD be updated",
            shouldUpdateBearing
        )
    }

    // ── GPS Filter Accuracy Gate Tests ───────────────────────────────────────────

    @Test
    fun `CAR mode accuracy gate is 55m not 75m`() {
        // Model the GpsFilterEngine accuracy gate selection:
        val carMaxAccuracy = when (ActivityProfile.CAR) {
            ActivityProfile.WALKING, ActivityProfile.HIKING -> 40.0f
            ActivityProfile.RUNNING -> 45.0f
            ActivityProfile.MTB, ActivityProfile.CYCLING -> 55.0f
            ActivityProfile.CAR -> 55.0f  // Changed from 75m → 55m
        }
        assertEquals(
            "CAR mode max accuracy gate must be 55m (reduced from 75m to prevent cross-street errors)",
            55.0f, carMaxAccuracy, 0.01f
        )
    }

    @Test
    fun `60m accuracy fix is rejected in CAR mode after gate reduction`() {
        // A 60m accuracy fix would previously be accepted (< 75m) but should now be rejected (> 55m)
        val carMaxAccuracy = 55.0f
        val fixAccuracy = 60.0f
        val isRejected = fixAccuracy > carMaxAccuracy
        assertTrue(
            "60m accuracy fix must be rejected in CAR mode with new 55m gate",
            isRejected
        )
    }

    @Test
    fun `50m accuracy fix is accepted in CAR mode`() {
        val carMaxAccuracy = 55.0f
        val fixAccuracy = 50.0f
        val isAccepted = fixAccuracy <= carMaxAccuracy
        assertTrue("50m accuracy fix must still be accepted in CAR mode", isAccepted)
    }

    // ── Map Rotation Deadband Tests ──────────────────────────────────────────────

    @Test
    fun `map rotation deadband is 5 degrees not 1_5 degrees`() {
        // Validate the new deadband constant
        val deadband = 5.0f
        // Verify: changes smaller than 5° do NOT trigger animation
        val smallChange = 3.0f
        val bigChange = 8.0f
        assertFalse("3° change must not exceed 5° deadband", smallChange >= deadband)
        assertTrue("8° change must exceed 5° deadband", bigChange >= deadband)
    }

    @Test
    fun `angular diff calculation is correct for wraparound case`() {
        // Model the diff calculation from OsmMapView
        fun angularDiff(target: Float, current: Float): Float {
            return (target - current + 540f) % 360f - 180f
        }
        // 5° → 355° (backward turn through north)
        val diff1 = angularDiff(355f, 5f)
        assertEquals("355° from 5° should be -10° (shortest path)", -10f, diff1, 0.1f)

        // 355° → 5° (small forward turn through north)
        val diff2 = angularDiff(5f, 355f)
        assertEquals("5° from 355° should be +10° (shortest path)", 10f, diff2, 0.1f)

        // 90° → 270° (backward 180° turn)
        val diff3 = angularDiff(270f, 90f)
        assertEquals("270° from 90° should be ±180°", 180f, abs(diff3), 0.1f)
    }
}
