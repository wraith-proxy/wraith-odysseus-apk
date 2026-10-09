package com.thomaspeoples.wraithodysseus.ui.tools.research

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The progress reducer, the phase text, the history list and the small helpers. */
class ResearchLogicTest {

    private fun job(id: String = "rp-1", status: String = "running") = LiveJob(id = id, query = "q", status = status, startedAtMs = 1_000)

    private fun frame(json: String) = JSONObject(json)

    @Test
    fun aProgressFrameReplacesTheProgressAndKeepsTheJobRunning() {
        val next = applyFrame(job(), frame("""{"phase":"searching","round":2,"queries":5,"status":"running","category":"product","mode":"research"}"""))
        assertEquals(Progress(phase = "searching", round = 2, queries = 5), next.progress)
        assertEquals("product", next.category)
        assertTrue(next.running)
    }

    @Test
    fun framesCarryTheSourceStateAndTheTraces() {
        val next = applyFrame(
            job(),
            frame(
                """{"phase":"reading","status":"running","source_state":"4 read",
                   "action_trace":[{"round":1,"tool":"search","query":"a"}],
                   "navigation_trace":[{"tool":"fetch","status":"ok","url":"https://e.com"}]}""",
            ),
        )
        assertEquals("4 read", next.sourceState)
        assertEquals("search: a", next.actionTrace.single().target)
        assertEquals("https://e.com", next.navigationTrace.single().target)
    }

    @Test
    fun aFrameWithoutTracesLeavesTheEarlierOnesAlone() {
        val seeded = applyFrame(job(), frame("""{"phase":"reading","status":"running","action_trace":[{"tool":"search","query":"a"}]}"""))
        val next = applyFrame(seeded, frame("""{"phase":"analyzing","status":"running"}"""))
        assertEquals(1, next.actionTrace.size)
        assertEquals("analyzing", next.progress.phase)
    }

    @Test
    fun theFirstModelReportedSticks() {
        val a = applyFrame(job(), frame("""{"phase":"probing","model":"m1","status":"running"}"""))
        val b = applyFrame(a, frame("""{"phase":"planning","model":"m2","status":"running"}"""))
        assertEquals("m1", b.model)
    }

    @Test
    fun theFinalFrameEndsTheJobAndKeepsTheLastProgress() {
        val mid = applyFrame(job(), frame("""{"phase":"writing","total_sources":7,"status":"running"}"""))
        val done = applyFrame(mid, frame("""{"status":"done","final":true}"""))
        assertEquals("done", done.status)
        assertEquals(7, done.progress.totalSources)
        assertNull(done.error)
    }

    @Test
    fun aFinalErrorCarriesItsText() {
        val failed = applyFrame(job(), frame("""{"status":"error","final":true,"error":"model refused"}"""))
        assertEquals("error", failed.status)
        assertEquals("model refused", failed.error)
    }

    @Test
    fun aFinalCancelledStaysCancelledAndAnythingElseIsAnError() {
        assertEquals("cancelled", applyFrame(job(), frame("""{"status":"cancelled","final":true}""")).status)
        assertEquals("error", applyFrame(job(), frame("""{"status":"weird","final":true}""")).status)
    }

    @Test
    fun notFoundMeansTheServerForgotTheJob() {
        val next = applyFrame(job(), frame("""{"status":"not_found"}"""))
        assertEquals("error", next.status)
        assertEquals("The server no longer has this research", next.error)
    }

    @Test
    fun theStatusFallbackReducesLikeTheStream() {
        val running = applyStatus(job(), frame("""{"status":"running","progress":{"phase":"analyzing","round":3,"total_findings":12},"source_state":"s","category":"howto"}"""))
        assertEquals(12, running.progress.totalFindings)
        assertEquals("howto", running.category)
        assertTrue(running.running)
        assertEquals("done", applyStatus(running, frame("""{"status":"done","progress":{}}""")).status)
        assertEquals("error", applyStatus(running, frame("""{"status":"error","progress":{}}""")).status)
        assertEquals("cancelled", applyStatus(running, frame("""{"status":"cancelled"}""")).status)
    }

    @Test
    fun formatPhaseMatchesTheWeb() {
        assertEquals("Starting...", formatPhase(Progress()))
        assertEquals("Probing model...", formatPhase(Progress(phase = "probing")))
        assertEquals("Planning research strategy...", formatPhase(Progress(phase = "planning")))
        assertEquals("Round 2: Searching (4 queries)", formatPhase(Progress(phase = "searching", round = 2, queries = 4)))
        assertEquals("Round 2/6: Reading 9 sources", formatPhase(Progress(phase = "reading", round = 2, totalSources = 9), maxRounds = 6))
        assertEquals("Analyzing 12 findings", formatPhase(Progress(phase = "analyzing", totalFindings = 12)))
        assertEquals("Writing report -- 7 sources", formatPhase(Progress(phase = "writing", round = 3, totalSources = 7)))
        assertEquals("Research timed out after 5s", formatPhase(Progress(phase = "error", message = "Research timed out after 5s")))
        assertEquals("navigating", formatPhase(Progress(phase = "navigating")))
    }

    @Test
    fun progressPercentUsesTheCapOrEight() {
        assertEquals(50, progressPercent(4, 0, "research"))
        assertEquals(100, progressPercent(20, 0, "research"))
        assertEquals(33, progressPercent(2, 6, "research"))
        assertEquals(68, progressPercent(0, 0, "explain"))
        assertEquals(0, progressPercent(0, 0, "research"))
    }

    @Test
    fun elapsedIsMinutesAndSeconds() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:07", formatElapsed(7_999))
        assertEquals("2:05", formatElapsed(125_000))
        assertEquals("0:00", formatElapsed(-5))
    }

    @Test
    fun mergeAdoptsNewJobsAndKeepsWhatItAlreadyFollows() {
        val mine = job("a").copy(progress = Progress(phase = "writing"), settings = ResearchSettings(maxRounds = 3))
        val failed = job("b", status = "error")
        val merged = mergeActive(listOf(mine, failed), listOf(job("a"), job("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("writing", merged[0].progress.phase)
        assertEquals(3, merged[0].settings?.maxRounds)
        assertEquals("error", merged[1].status)
    }

    private fun item(id: String, q: String, completed: Double, sources: Int, cat: String = "", status: String = "done") =
        LibraryItem(id, q, cat, "research", sources, status, "", 0.0, completed)

    private val library = listOf(
        item("1", "Plex NAS", 300.0, 14, "product"),
        item("2", "fasting", 200.0, 0),
        item("3", "Alpha beta", 100.0, 5, "product"),
        item("4", "broken", 400.0, 2, status = "error"),
    )

    @Test
    fun historyShowsFinishedReportsNewestFirst() {
        assertEquals(listOf("1", "2", "3"), visibleHistory(library, "", HistorySort.Recent, null).map { it.id })
        assertEquals(listOf("3", "2", "1"), visibleHistory(library, "", HistorySort.Oldest, null).map { it.id })
    }

    @Test
    fun historySortsBySourcesAndAlphabetically() {
        assertEquals(listOf("1", "3", "2"), visibleHistory(library, "", HistorySort.Sources, null).map { it.id })
        assertEquals(listOf("3", "2", "1"), visibleHistory(library, "", HistorySort.Alpha, null).map { it.id })
    }

    @Test
    fun historySearchIsCaseInsensitiveOnTheQuestionAndCombinesWithTheCategory() {
        assertEquals(listOf("1"), visibleHistory(library, "  plex ", HistorySort.Recent, null).map { it.id })
        assertEquals(listOf("1", "3"), visibleHistory(library, "", HistorySort.Recent, "product").map { it.id })
        assertEquals(listOf("3"), visibleHistory(library, "ALPHA", HistorySort.Recent, "product").map { it.id })
        assertTrue(visibleHistory(library, "zzz", HistorySort.Recent, null).isEmpty())
    }

    @Test
    fun categoryChipsCountAndKeepTheWebOrder() {
        val all = library + item("5", "x", 1.0, 1, "factcheck") + item("6", "y", 1.0, 1, "weird")
        assertEquals(listOf("" to 1, "product" to 2, "factcheck" to 1, "weird" to 1), historyCategories(all.filter { it.status == "done" }).let { it })
    }

    @Test
    fun copyTextHasTheQuestionTheReportFindingsAndSources() {
        val r = ResearchResult(
            result = "Body",
            sources = listOf(Source("One", "https://e.com/1", "", "", null), Source("", "https://e.com/2", "", "", null)),
            findings = listOf(Finding("F", "https://e.com/1", "sum"), Finding("", "https://e.com/2", "more")),
            sourceState = "", category = "", mode = "", actionTrace = emptyList(), navigationTrace = emptyList(),
        )
        assertEquals(
            "# Why\n\nBody\n\n---\n## Raw Findings\n\n### F\nSource: https://e.com/1\nsum\n\n### Untitled\nSource: https://e.com/2\nmore\n" +
                "\n\n---\n## Sources\n- [One](https://e.com/1)\n- [https://e.com/2](https://e.com/2)",
            copyText("Why", r),
        )
        assertEquals("# Why\n\nBody", copyText("Why", r.copy(sources = emptyList(), findings = emptyList())))
    }

    @Test
    fun onlyHttpLinksAreLinks() {
        assertEquals("https://e.com/a", safeHref(" https://e.com/a "))
        assertEquals("HTTP://E.COM", safeHref("HTTP://E.COM"))
        assertNull(safeHref("javascript:alert(1)"))
        assertNull(safeHref("file:///etc/passwd"))
        assertNull(safeHref("intent://x#Intent;end"))
        assertNull(safeHref("/relative"))
        assertNull(safeHref("https://"))
        assertNull(safeHref(null))
    }

    @Test
    fun sourceBadgesSkipTheDefaultRetrieval() {
        assertEquals("docs · browser · 91", sourceBadges(Source("t", "u", "docs", "browser", 91)))
        assertEquals("", sourceBadges(Source("t", "u", "", "fetch", null)))
        assertEquals("0", sourceBadges(Source("t", "u", "", "", 0)))
    }

    @Test
    fun theStartBodyDropsEveryDefault() {
        assertEquals("""{"query":"q"}""", ResearchApi.startBody("q", ResearchSettings()).toString())
        val body = ResearchApi.startBody("q", ResearchSettings(maxRounds = 0, category = "product"))
        assertEquals("product", body.getString("category"))
        assertTrue(!body.has("max_rounds"))
    }
}
