package com.thomaspeoples.wraithodysseus.ui.chat.parts

import com.thomaspeoples.wraithodysseus.chat.MemoryUsed
import com.thomaspeoples.wraithodysseus.chat.MessageMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsFormatTest {

    // -- the footer's fallback order -----------------------------------------

    @Test
    fun speedComesFirst() {
        assertEquals("52.40 tok/s", footerLabel(MessageMetrics(tokensPerSecond = 52.4, costUsd = 0.5, responseTime = 3.0)))
    }

    @Test
    fun aZeroSpeedIsStillSpeed() {
        assertEquals("0.00 tok/s", footerLabel(MessageMetrics(tokensPerSecond = 0.0, costUsd = 0.5, responseTime = 3.0)))
    }

    @Test
    fun costIsNextWithFourDecimalsUnderACent() {
        assertEquals("\$0.0012", footerLabel(MessageMetrics(costUsd = 0.00123, responseTime = 3.0)))
        assertEquals("\$0.123", footerLabel(MessageMetrics(costUsd = 0.1234)))
    }

    @Test
    fun responseTimeIsTheLastResort() {
        assertEquals("3.210s", footerLabel(MessageMetrics(responseTime = 3.21)))
    }

    @Test
    fun nothingKnownMeansNoFigure() {
        assertNull(footerLabel(MessageMetrics(inputTokens = 10)))
    }

    // -- the ring ---------------------------------------------------------

    @Test
    fun ringColoursSwitchAt70And85() {
        assertEquals(RingLevel.Ok, ringLevel(0.1))
        assertEquals(RingLevel.Ok, ringLevel(69.9))
        assertEquals(RingLevel.Warn, ringLevel(70.0))
        assertEquals(RingLevel.Warn, ringLevel(84.9))
        assertEquals(RingLevel.High, ringLevel(85.0))
        assertEquals(RingLevel.High, ringLevel(100.0))
    }

    @Test
    fun theRingNeedsSomeContextAndAFigure() {
        assertEquals(71.5, ringPercent(MessageMetrics(responseTime = 1.0, contextPercent = 71.5))!!, 0.0)
        assertNull(ringPercent(MessageMetrics(responseTime = 1.0, contextPercent = 0.0)))
        assertNull(ringPercent(MessageMetrics(inputTokens = 5, contextPercent = 40.0)))
    }

    // -- the stats popup ----------------------------------------------------

    private val full = MessageMetrics(
        responseTime = 3.21, tokensPerSecond = 52.4, tpsSource = "computed", inputTokens = 1200, outputTokens = 340,
        realUsage = false, contextPercent = 71.5, contextLength = 32768, model = "org/qwen3", costUsd = 0.00123,
        timeToFirstToken = 0.41, prepTime = 0.25, modelWaitTime = 0.16,
        prepBreakdown = listOf("tool_select" to 0.1), toolSchemaCount = 1200, agentRounds = 3, toolCalls = 2,
    )

    @Test
    fun theStatsPopupHasEveryRowOfTheWeb() {
        val rows = statsSections(full).flatten().toMap()
        assertEquals("qwen3", rows["Model"])
        assertEquals("1,200 tokens~", rows["Input"])
        assertEquals("340 tokens~", rows["Output"])
        assertEquals("1,540 tokens", rows["Total"])
        assertEquals("52.40 tok/s", rows["Speed (wall)"])
        assertEquals("3.210s", rows["Time"])
        assertEquals("0.250s", rows["Prep"])
        assertEquals("0.160s", rows["Model wait"])
        assertEquals("0.410s", rows["TTFT"])
        assertEquals("1,200", rows["Tool schemas"])
        assertEquals("3", rows["Agent rounds"])
        assertEquals("2", rows["Tool calls"])
        assertEquals("\$0.0012", rows["Cost"])
        assertEquals("0.100s", rows["tool select"])
        assertEquals("72% used", rows["Context"])
    }

    @Test
    fun realUsageDropsTheTilde() {
        val rows = statsSections(full.copy(realUsage = true, tpsSource = "backend")).flatten().toMap()
        assertEquals("1,200 tokens", rows["Input"])
        assertTrue("Speed" in rows)
    }

    @Test
    fun missingSpeedReadsNotAvailableAndEmptySectionsVanish() {
        val sections = statsSections(MessageMetrics(inputTokens = 1, outputTokens = 2))
        assertEquals("n/a", sections.flatten().toMap()["Speed"])
        assertTrue(sections.none { it.isEmpty() })
        assertFalse(sections.flatten().any { it.first == "Context" })
    }

    @Test
    fun theContextWindowRows() {
        val rows = contextRows(full).toMap()
        assertEquals("1,200", rows["Used"])
        assertEquals("32,768", rows["Total"])
        assertEquals("72%", rows["Usage"])
        assertEquals("?", contextRows(MessageMetrics()).toMap()["Total"])
    }

    // -- the memory chip -------------------------------------------------

    @Test
    fun theMemoryChipCountsPinnedThenRecalled() {
        val m = listOf(MemoryUsed("pinned", "a"), MemoryUsed("recalled", "b"), MemoryUsed("pinned", "c"))
        assertEquals("2 pinned, 1 recalled", memoryChip(m))
        assertEquals("1 recalled", memoryChip(listOf(MemoryUsed("recalled", "x"))))
        assertEquals("", memoryChip(emptyList()))
    }

    // -- links, output, diff ----------------------------------------------

    @Test
    fun onlyWebAddressesOpen() {
        assertTrue(isWebUrl("https://a.example"))
        assertTrue(isWebUrl("HTTP://a.example"))
        assertFalse(isWebUrl("javascript:alert(1)"))
        assertFalse(isWebUrl("intent://x"))
    }

    @Test
    fun longOutputIsCutAndReportsHowMuch() {
        assertEquals("short" to 0, truncateOutput("short"))
        val (shown, cut) = truncateOutput("a".repeat(OUTPUT_LIMIT + 50))
        assertEquals(OUTPUT_LIMIT, shown.length)
        assertEquals(50, cut)
    }

    @Test
    fun diffLinesDropTheirMarkerAndKeepTheKind() {
        val lines = diffLines("--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n same")
        assertEquals(
            listOf(DiffKind.Meta, DiffKind.Meta, DiffKind.Hunk, DiffKind.Del, DiffKind.Add, DiffKind.Context),
            lines.map { it.kind },
        )
        assertEquals(listOf("old", "new", "same"), lines.drop(3).map { it.text })
    }
}
