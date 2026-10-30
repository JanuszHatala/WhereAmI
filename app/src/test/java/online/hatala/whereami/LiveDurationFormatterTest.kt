package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDurationFormatterTest {

    @Test
    fun testFormatCompactLiveDuration() {
        // Zero / negative
        assertEquals("0m", LiveGuest.formatCompactLiveDuration(0L))
        assertEquals("0m", LiveGuest.formatCompactLiveDuration(-1000L))

        // Minutes only (< 1h)
        assertEquals("15m", LiveGuest.formatCompactLiveDuration(15 * 60_000L))
        assertEquals("59m", LiveGuest.formatCompactLiveDuration(59 * 60_000L))

        // Hours & minutes (< 24h)
        assertEquals("1h", LiveGuest.formatCompactLiveDuration(60 * 60_000L))
        assertEquals("5h 12m", LiveGuest.formatCompactLiveDuration((5 * 60 + 12) * 60_000L))
        assertEquals("23h 59m", LiveGuest.formatCompactLiveDuration((23 * 60 + 59) * 60_000L))

        // Days & hours (1d to 6d)
        assertEquals("1d", LiveGuest.formatCompactLiveDuration(24 * 3600_000L))
        assertEquals("2d 7h", LiveGuest.formatCompactLiveDuration((2 * 24 + 7) * 3600_000L))
        assertEquals("6d 6h", LiveGuest.formatCompactLiveDuration(150 * 3600_000L)) // 150h = 6d 6h

        // Weeks & days (7d to 29d)
        assertEquals("1w", LiveGuest.formatCompactLiveDuration(7 * 24 * 3600_000L)) // 7 days = 1 week
        assertEquals("1w 2d", LiveGuest.formatCompactLiveDuration(221 * 3600_000L)) // 221h = 9d 5h = 1w 2d
        assertEquals("2w 3d", LiveGuest.formatCompactLiveDuration(17 * 24 * 3600_000L)) // 17 days = 2w 3d
        assertEquals("4w 1d", LiveGuest.formatCompactLiveDuration(29 * 24 * 3600_000L)) // 29 days = 4w 1d

        // Months & days (30d to 364d)
        assertEquals("1mo", LiveGuest.formatCompactLiveDuration(30 * 24 * 3600_000L))
        assertEquals("1mo 5d", LiveGuest.formatCompactLiveDuration(35 * 24 * 3600_000L))
        assertEquals("2mo", LiveGuest.formatCompactLiveDuration(60 * 24 * 3600_000L))

        // Years & months (>= 365d)
        assertEquals("1y", LiveGuest.formatCompactLiveDuration(365 * 24 * 3600_000L))
        assertEquals("1y 2mo", LiveGuest.formatCompactLiveDuration(425 * 24 * 3600_000L))
        assertEquals("1y 10d", LiveGuest.formatCompactLiveDuration(375 * 24 * 3600_000L))
    }
}
