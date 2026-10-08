package com.thomaspeoples.wraithodysseus.ui.email

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailUiTest {

    private val now = Instant.parse("2026-10-07T20:00:00Z")

    private fun epoch(iso: String) = Instant.parse(iso).epochSecond.toDouble()

    @Test
    fun listDateNarrowsWithAge() {
        assertEquals("17:30", listDate(epoch("2026-10-07T17:30:00Z"), now, ZoneOffset.UTC))
        assertEquals("20 Sep", listDate(epoch("2026-09-20T10:13:20Z"), now, ZoneOffset.UTC))
        assertEquals("1 Jan 2025", listDate(epoch("2025-01-01T12:00:00Z"), now, ZoneOffset.UTC))
    }

    @Test
    fun theFallbackAccountRoundTripsThroughTheRoute() {
        assertEquals("acc-home", accountFromArg(accountArg("acc-home")))
        assertNull(accountFromArg(accountArg(null)))
    }
}
