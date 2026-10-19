package janush.tech.whereami

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A paused car trip resumes only on accurate, sustained travel at driving speed (trip 233, 16:22). */
class ResumeMotionGateTest {

    private val lat0 = 49.8
    private val lng0 = 19.0
    private fun north(m: Double) = lat0 + m / 111_320.0
    private fun east(m: Double) = lng0 + m / (111_320.0 * kotlin.math.cos(Math.toRadians(lat0)))

    private fun ResumeMotionGate.car(t: Long, n: Double, e: Double, kmh: Float, acc: Float?) =
        offer(t, north(n), east(e), kmh, acc, 10f, 10_000L, 30.0)

    @Test
    fun `walking around indoors with noisy speed and jumping fixes never resumes`() {
        val gate = ResumeMotionGate()
        val rnd = Random(1)
        var resumed = false
        for (s in 0 until 600) {
            val kmh = (5.0 + rnd.nextDouble() * 15.0).toFloat()
            val acc = (20.0 + rnd.nextDouble() * 150.0).toFloat()
            if (gate.car(s * 1_000L, rnd.nextDouble() * 80.0 - 40.0, rnd.nextDouble() * 80.0 - 40.0, kmh, acc)) resumed = true
        }
        assertFalse(resumed)
    }

    @Test
    fun `walking outside with good accuracy never resumes a car trip`() {
        val gate = ResumeMotionGate()
        var resumed = false
        for (s in 0 until 300) if (gate.car(s * 1_000L, s * 1.4, 0.0, 5.0f, 4f)) resumed = true
        assertFalse(resumed)
    }

    @Test
    fun `occasional fast accurate samples among noise do not add up`() {
        val gate = ResumeMotionGate()
        var resumed = false
        for (s in 0 until 120) {
            val fast = s % 4 == 0
            if (gate.car(s * 1_000L, s * 3.0, 0.0, if (fast) 12f else 4f, if (fast) 10f else 30f)) resumed = true
        }
        assertFalse(resumed)
    }

    @Test
    fun `driving off resumes after the confirmation window`() {
        val gate = ResumeMotionGate()
        var resumedAt = -1L
        for (s in 0 until 30) {
            val t = s * 1_000L
            if (resumedAt < 0 && gate.car(t, s * 8.0, 0.0, 29f, 6f)) resumedAt = t
        }
        assertTrue("resumed at $resumedAt", resumedAt in 10_000L..12_000L)
    }

    @Test
    fun `one inaccurate fix restarts the confirmation`() {
        val gate = ResumeMotionGate()
        var resumedAt = -1L
        for (s in 0 until 40) {
            val t = s * 1_000L
            val acc = if (s == 8) 60f else 6f
            if (resumedAt < 0 && gate.car(t, s * 8.0, 0.0, 29f, acc)) resumedAt = t
        }
        assertTrue("resumed at $resumedAt", resumedAt >= 19_000L)
    }
}
