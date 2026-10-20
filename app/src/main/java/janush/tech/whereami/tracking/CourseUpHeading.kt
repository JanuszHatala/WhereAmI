package janush.tech.whereami.tracking

/**
 * Heading that drives course-up map rotation.
 *
 * The compass and the slow-mode path tangent may drive the **arrow** (smooth, line-following), but they
 * must not rotate the map: compass noise and multipath in the pencil trail make course-up wave while the
 * user walks straight. Prefer the filter heading whenever it is confident; otherwise the visible motion
 * course of the cursor.
 */
object CourseUpHeading {
    fun resolve(
        isMoving: Boolean,
        isAnchored: Boolean,
        headingConfident: Boolean,
        headingDeg: Double,
        motionCourseDeg: Double?
    ): Double? {
        if (!isMoving || isAnchored) return null
        if (headingConfident) return headingDeg
        return motionCourseDeg
    }
}
