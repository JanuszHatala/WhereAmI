package janush.tech.whereami.tracking

import janush.tech.whereami.ActivityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseUpHeadingTest {

    @Test
    fun `prefers filter heading when confident and moving`() {
        val h = CourseUpHeading.resolve(
            isMoving = true,
            isAnchored = false,
            headingConfident = true,
            headingDeg = 95.0,
            motionCourseDeg = 110.0
        )
        assertEquals(95.0, h!!, 1e-9)
    }

    @Test
    fun `falls back to motion course when filter heading is not confident`() {
        val h = CourseUpHeading.resolve(
            isMoving = true,
            isAnchored = false,
            headingConfident = false,
            headingDeg = 95.0,
            motionCourseDeg = 110.0
        )
        assertEquals(110.0, h!!, 1e-9)
    }

    @Test
    fun `null when stationary or anchored`() {
        assertNull(
            CourseUpHeading.resolve(false, false, true, 95.0, 110.0)
        )
        assertNull(
            CourseUpHeading.resolve(true, true, true, 95.0, 110.0)
        )
    }

    @Test
    fun `walking slew ignores sub three degree target noise`() {
        val slew = RotationSlew.forProfile(TrackingBridge.profileFor(ActivityProfile.WALKING))
        slew.snap(96.0)
        // 2 deg wobble must not move the held target
        assertEquals(96.0, slew.step(97.5, 0.05), 1e-9)
        assertEquals(96.0, slew.angleDeg, 1e-9)
        // 4 deg change is accepted
        val after = slew.step(100.0, 0.5)
        assertTrue(kotlin.math.abs(shortestDeltaDeg(96.0, after)) > 0.5)
    }

    @Test
    fun `pencil min step floor is one metre at walking pace and two metres for vehicles`() {
        assertEquals(1.0, PencilTrail.minStepM(1.4), 1e-9)
        assertEquals(1.0, PencilTrail.minStepM(0.5), 1e-9)
        assertEquals(3.0, PencilTrail.minStepM(1.4, vehicle = true), 1e-9)
        assertEquals(4.0, PencilTrail.minStepM(40.0), 1e-9)
    }
}
