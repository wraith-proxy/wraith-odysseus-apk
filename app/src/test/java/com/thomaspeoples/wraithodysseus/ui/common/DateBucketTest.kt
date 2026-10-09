package com.thomaspeoples.wraithodysseus.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateBucketTest {
    private val london = ZoneId.of("Europe/London")
    // Thursday 8 October 2026, 19:00 in London (BST).
    private val now = Instant.parse("2026-10-08T18:00:00Z")
    private val today = LocalDate.of(2026, 10, 8)

    private fun ago(days: Long) = today.minusDays(days).atTime(12, 0).toInstant(ZoneOffset.UTC)
    private fun bucket(t: Instant?, locale: Locale = Locale.UK) = dateBucket(t, now, london, locale)

    @Test
    fun stepsMatchTheWebUi() {
        assertEquals("Today", bucket(ago(0)))
        assertEquals("Yesterday", bucket(ago(1)))
        assertEquals("Monday", bucket(ago(3)))
        assertEquals("Friday", bucket(ago(6)))
        assertEquals("1 October", bucket(ago(7)))
        assertEquals("28 September", bucket(ago(10)))
        assertEquals("September 28", bucket(ago(10), Locale.US))
        assertEquals("30 days ago", bucket(ago(30)))
        assertEquals("30 days ago", bucket(ago(45)))
        assertEquals("90 days ago", bucket(ago(95)))
        assertEquals("150 days ago", bucket(ago(179)))
        assertEquals("6 months ago", bucket(ago(180)))
        assertEquals("6 months ago", bucket(ago(364)))
        assertEquals("1 year ago", bucket(ago(365)))
        assertEquals("2 years ago", bucket(ago(800)))
        assertEquals("Older", bucket(null))
    }

    @Test
    fun anotherYearIsSpelledOut() {
        val january = Instant.parse("2026-01-05T12:00:00Z")
        val december = LocalDate.of(2025, 12, 20).atTime(12, 0).toInstant(ZoneOffset.UTC)
        assertEquals("20 December 2025", dateBucket(december, january, london, Locale.UK))
        assertEquals("December 20, 2025", dateBucket(december, january, london, Locale.US))
    }

    @Test
    fun naiveServerTimesAreUtc() {
        // 23:30 UTC on the 7th is 00:30 on the 8th in London.
        assertEquals("Today", bucket(parseServerTime("2026-10-07T23:30:00.123456")))
        assertEquals(Instant.parse("2026-10-07T18:02:10.500Z"), parseServerTime("2026-10-07T18:02:10.500000"))
        assertEquals(Instant.parse("2026-10-07T17:02:10Z"), parseServerTime("2026-10-07T18:02:10+01:00"))
        assertEquals(Instant.parse("2026-10-07T18:02:10Z"), parseServerTime("2026-10-07 18:02:10"))
        assertNull(parseServerTime(""))
        assertNull(parseServerTime(null))
        assertNull(parseServerTime("yesterday"))
    }
}
