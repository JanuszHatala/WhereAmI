package janush.tech.whereami.tracking

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure math for the compass arrow (no Android types, unit-testable).
 *
 * The rotation-vector sensor delivers the device attitude as a quaternion. The arrow must show where the
 * *person* faces, which depends on how the phone is held:
 *  - flat in the hand (screen up): the top edge points forward,
 *  - upright (screen towards the user): the back of the phone points forward,
 * with a smooth blend in between so the arrow does not flip while the phone is raised.
 */
object CompassMath {
    /** |screen normal . up| above this: phone is flat. */
    private const val FLAT_COS = 0.80
    /** ... below this: phone is upright. */
    private const val UPRIGHT_COS = 0.45
    private const val MIN_HORIZONTAL = 0.25

    /** Row-major 3x3 device-to-world (east, north, up) rotation matrix from rotation-vector values. */
    fun rotationMatrix(v: FloatArray): DoubleArray? {
        if (v.size < 3) return null
        val x = v[0].toDouble()
        val y = v[1].toDouble()
        val z = v[2].toDouble()
        val w = if (v.size > 3 && v[3] != 0f) v[3].toDouble() else {
            val t = 1.0 - x * x - y * y - z * z
            if (t > 0.0) sqrt(t) else 0.0
        }
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-6) return null
        val qw = w / n; val qx = x / n; val qy = y / n; val qz = z / n
        return doubleArrayOf(
            1 - 2 * qy * qy - 2 * qz * qz, 2 * qx * qy - 2 * qz * qw, 2 * qx * qz + 2 * qy * qw,
            2 * qx * qy + 2 * qz * qw, 1 - 2 * qx * qx - 2 * qz * qz, 2 * qy * qz - 2 * qx * qw,
            2 * qx * qz - 2 * qy * qw, 2 * qy * qz + 2 * qx * qw, 1 - 2 * qx * qx - 2 * qy * qy
        )
    }

    /** Direction the user faces, degrees clockwise from MAGNETIC north, or null when it is not defined. */
    fun facingMagneticDeg(r: DoubleArray): Double? {
        // Device Y (top edge) and -Z (back of the phone) in world coordinates, horizontal parts.
        val yE = r[1]; val yN = r[4]
        val bE = -r[2]; val bN = -r[5]
        val flat = smoothstep((kotlin.math.abs(r[8]) - UPRIGHT_COS) / (FLAT_COS - UPRIGHT_COS))
        val yLen = hypot(yE, yN)
        val bLen = hypot(bE, bN)
        var e = 0.0
        var n = 0.0
        if (yLen > 1e-6) { e += flat * yE / yLen; n += flat * yN / yLen }
        if (bLen > 1e-6) { e += (1 - flat) * bE / bLen; n += (1 - flat) * bN / bLen }
        if (hypot(e, n) < MIN_HORIZONTAL) return null
        return wrapDeg360(Math.toDegrees(atan2(e, n)))
    }

    /** Convenience: sensor values + declination to a true-north heading. */
    fun facingTrueDeg(values: FloatArray, declinationDeg: Double): Double? {
        val r = rotationMatrix(values) ?: return null
        val m = facingMagneticDeg(r) ?: return null
        return wrapDeg360(m + declinationDeg)
    }

    private fun smoothstep(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }
}
