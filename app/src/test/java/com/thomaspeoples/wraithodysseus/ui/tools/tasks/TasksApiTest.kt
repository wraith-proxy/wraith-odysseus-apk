package com.thomaspeoples.wraithodysseus.ui.tools.tasks

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TasksApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: TasksApi

    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    @Before
    fun setUp() {
        server.start()
        api = TasksApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(name: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(fixture(name)))

    private fun errorOf(block: suspend () -> Unit): ApiException =
        runBlocking { runCatching { block() }.exceptionOrNull() } as? ApiException ?: throw AssertionError("expected ApiException")

    // -- calls ------------------------------------------------------------

    @Test
    fun listAsksForLastRunAndParsesTasks() = runBlocking {
        reply("tasks_list.json")
        val tasks = api.list()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/tasks?include_last_run=true", req.path)
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
        // The row without an id is dropped.
        assertEquals(listOf("t1", "t2", "t3"), tasks.map { it.id })
        val t1 = tasks[0]
        assertEquals("research", t1.taskType)
        assertEquals("daily", t1.schedule)
        assertEquals(12, t1.runCount)
        assertEquals("success", t1.lastRunStatus)
        assertEquals("Top story: ...", t1.lastRunResult)
        assertTrue(t1.canRun)
        val t2 = tasks[1]
        assertEquals("paused", t2.status)
        assertEquals(4, t2.scheduledDay)
        assertNull(t2.nextRun)
        assertNull(t2.lastRunResult)
        assertEquals("email", t2.outputTarget)
        assertNull(t2.scheduledDate)
        assertEquals("", tasks[2].prompt.takeIf { it == "null" } ?: "")
        assertTrue(tasks[2].isBuiltin)
    }

    @Test
    fun pauseResumeRunPostAndCheckBodies() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"paused"}"""))
        api.pause("t1")
        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"active","next_run":null}"""))
        api.resume("t1")
        server.enqueue(MockResponse().setBody("""{"ok":true,"message":"Task triggered"}"""))
        api.runNow("t1")
        for (action in listOf("pause", "resume", "run")) {
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/tasks/t1/$action", req.path)
        }
    }

    @Test
    fun runNowConflictAndForbiddenShowTheServersText() {
        reply("tasks_run_conflict.json", 409)
        val conflict = errorOf { api.runNow("t1") }
        assertEquals(409, conflict.status)
        assertEquals("Task is already running", conflict.message)
        reply("tasks_action_forbidden.json", 403)
        assertEquals("Action 'run_script' requires admin privileges", errorOf { api.resume("t3") }.message)
    }

    @Test
    fun deleteSendsDelete() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api.delete("t2")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/tasks/t2", req.path)
    }

    @Test
    fun deleteOfMissingTaskIs404() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Task not found"}"""))
        assertEquals("Task not found", errorOf { api.delete("nope") }.message)
    }

    @Test
    fun runsParseAndPickTheTextTheWebShows() = runBlocking {
        reply("tasks_runs.json")
        val runs = api.runs("t1")
        assertEquals("/api/tasks/t1/runs?limit=20", server.takeRequest().path)
        assertEquals(listOf("success", "error", "running"), runs.map { it.status })
        assertEquals("Top story: ...", runs[0].text)
        assertEquals("openai/gpt-x", runs[0].model)
        assertEquals("Endpoint unreachable", runs[1].text)
        assertTrue(runs[1].isError)
        assertNull(runs[1].model)
        // Empty result and empty error: a dash, as the web shows.
        assertEquals("—", runs[2].text)
        assertFalse(runs[2].isError)
        assertNull(runs[2].finishedAt)
    }

    // -- parse and create -------------------------------------------------

    @Test
    fun parseSendsTheDescriptionAndReadsTheDraft() = runBlocking {
        reply("tasks_parse_ok.json")
        val d = api.parse("every weekday at 7am research AI news")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tasks/parse", req.path)
        assertEquals("every weekday at 7am research AI news", JSONObject(req.body.readUtf8()).getString("description"))
        assertEquals("research", d.taskType)
        assertEquals("cron", d.schedule)
        assertEquals("0 7 * * 1-5", d.cronExpression)
        assertEquals("email", d.outputTarget)
        assertEquals("Weekday AI news", d.name)
    }

    @Test
    fun parseFailureIsA200WithSuccessFalse() {
        reply("tasks_parse_fail.json")
        val e = errorOf { api.parse("whatever") }
        assertEquals("No model endpoint configured", e.message)
        server.enqueue(MockResponse().setBody("""{"success":true,"draft":{"schedule":"daily"}}"""))
        assertEquals("Could not extract a task instruction", errorOf { api.parse("x") }.message)
    }

    @Test
    fun createConvertsLocalTimeToUtcLikeTheWeb() = runBlocking {
        reply("tasks_parse_daily.json")
        val draft = api.parse("stretch daily at 7:30")
        server.takeRequest()
        reply("tasks_create_ok.json")
        val created = api.create(draft, paris, now)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tasks", req.path)
        val body = JSONObject(req.body.readUtf8())
        // Paris is UTC+2 on 9 October 2026.
        assertEquals("05:30", body.getString("scheduled_time"))
        assertEquals("daily", body.getString("schedule"))
        assertEquals("schedule", body.getString("trigger_type"))
        assertEquals("llm", body.getString("task_type"))
        assertEquals("session", body.getString("output_target"))
        assertEquals("Stretch reminder", body.getString("name"))
        assertEquals("Remind me to stretch", body.getString("prompt"))
        assertEquals("t9", created.id)
    }

    /**
     * The server (task_routes.py create) parses scheduled_date with the "Z"
     * swapped for +00:00 and then drops the zone without converting, and
     * compute_next_run keeps only a date strictly after now. So the wire value
     * must be UTC with a Z (what the web's toISOString sends), and a date equal
     * to now is already too late.
     */
    @Test
    fun onceDateIsSentAsUtcAndNowIsTooLate() {
        assertEquals("2026-10-12T05:30:00Z", TasksApi.localDateTimeToUtcIso("2026-10-12T07:30", paris))
        val base = TaskDraft("llm", null, "p", "once", null, null, null, null, null)
        assertTrue(TasksApi.onceProblem(base.copy(scheduledDate = "2026-10-09T14:00"), paris, now) != null)
        assertNull(TasksApi.onceProblem(base.copy(scheduledDate = "2026-10-09T14:01"), paris, now))
    }

    @Test
    fun onceDraftNeedsAFutureReadableDate() {
        val base = TaskDraft("llm", null, "p", "once", null, null, null, null, null)
        assertTrue(TasksApi.onceProblem(base, paris, now) != null)
        assertTrue(TasksApi.onceProblem(base.copy(scheduledDate = "tomorrow-ish"), paris, now) != null)
        assertTrue(TasksApi.onceProblem(base.copy(scheduledDate = "2001-01-01T07:30"), paris, now) != null)
        assertNull(TasksApi.onceProblem(base.copy(scheduledDate = "2999-01-01T07:30"), paris, now))
        assertNull(TasksApi.onceProblem(base.copy(schedule = "daily"), paris, now))
    }

    @Test
    fun parseTaskTurnsJsonNullIntoEmptyNotTheWordNull() {
        val t = TasksApi.parseTask(JSONObject("""{"id":"t1","name":null,"prompt":null,"status":null}"""))!!
        assertEquals("", t.prompt)
        assertEquals("", t.name)
        assertEquals("", t.status)
    }

    @Test
    fun createBodyForCronOnceAndWeekly() {
        val base = TaskDraft("llm", null, "p", "cron", "07:00", null, null, "0 7 * * 1-5", null)
        val cron = TasksApi.createBody(base, paris, now)
        assertEquals("0 7 * * 1-5", cron.getString("cron_expression"))
        assertFalse(cron.has("scheduled_time"))
        assertFalse(cron.has("name"))

        val once = TasksApi.createBody(base.copy(schedule = "once", scheduledDate = "2026-10-12T07:30"), paris, now)
        assertEquals("2026-10-12T05:30:00Z", once.getString("scheduled_date"))
        assertFalse(once.has("scheduled_time"))

        val weekly = TasksApi.createBody(base.copy(schedule = "weekly", scheduledDay = 4, scheduledTime = "17:30"), paris, now)
        assertEquals("15:30", weekly.getString("scheduled_time"))
        assertEquals(4, weekly.getInt("scheduled_day"))

        // No time stated: the server's own default, 09:00, local.
        val noTime = TasksApi.createBody(base.copy(schedule = "daily", scheduledTime = null), paris, now)
        assertEquals("07:00", noTime.getString("scheduled_time"))
    }

    @Test
    fun createErrorsAreShown() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Invalid cron expression"}"""))
        val d = TaskDraft("llm", null, "p", "cron", null, null, null, "bad", null)
        assertEquals("Invalid cron expression", errorOf { api.create(d, paris, now) }.message)
    }

    @Test
    fun createDoesNotRetryAfterADroppedConnection() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(fixture("tasks_create_ok.json")))
        val d = TaskDraft("llm", null, "p", "daily", "09:00", null, null, null, null)
        runCatching { runBlocking { api.create(d, paris, now) } }
        assertEquals(1, server.requestCount)
    }

    // -- schedule text ----------------------------------------------------

    @Test
    fun aWebhookTaskGetsItsTriggerUrlAndOthersNone() {
        val hook = task("""{"id":"t 1","trigger_type":"webhook","webhook_token":"tok_A-9"}""")
        assertEquals(server.url("/api/tasks/t%201/webhook/tok_A-9").toString(), api.webhookUrl(hook))
        assertNull(api.webhookUrl(task("""{"id":"t2","trigger_type":"webhook","webhook_token":null}""")))
        assertNull(api.webhookUrl(task("""{"id":"t3"}""")))
    }

    private fun task(json: String) = TasksApi.parseTask(JSONObject(json))!!

    private fun text(t: Task) = TasksApi.scheduleText(t, paris, now, Locale.US)

    @Test
    fun scheduleTextForEachKind() {
        val daily = task("""{"id":"a","schedule":"daily","scheduled_time":"06:00"}""")
        assertTrue(text(daily), text(daily).startsWith("Daily at 8:00"))
        val weekly = task("""{"id":"a","schedule":"weekly","scheduled_time":"15:30","scheduled_day":4}""")
        assertTrue(text(weekly), text(weekly).startsWith("Weekly on Friday at 5:30"))
        val monthly = task("""{"id":"a","schedule":"monthly","scheduled_time":"03:00","scheduled_day":22}""")
        assertTrue(text(monthly), text(monthly).startsWith("Monthly on 22nd at 5:00"))
        assertEquals("Cron: 0 7 * * 1-5", text(task("""{"id":"a","schedule":"cron","cron_expression":"0 7 * * 1-5"}""")))
        assertEquals("Cron: ?", text(task("""{"id":"a","schedule":"cron"}""")))
        assertEquals("Once", text(task("""{"id":"a","schedule":"once"}""")))
        val once = text(task("""{"id":"a","schedule":"once","scheduled_date":"2026-10-12T05:30:00Z"}"""))
        assertTrue(once, once.startsWith("Once on 10/12/26 at 7:30"))
        assertEquals("Webhook", text(task("""{"id":"a","trigger_type":"webhook"}""")))
        assertEquals("Every 1 session created", text(task("""{"id":"a","trigger_type":"event","trigger_event":"session_created","trigger_count":1}""")))
        assertEquals("Every 5 session createds", text(task("""{"id":"a","trigger_type":"event","trigger_event":"session_created","trigger_count":5}""")))
        assertEquals("—", text(task("""{"id":"a"}""")))
    }

    @Test
    fun ordinals() {
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(TasksApi::ordinal),
        )
    }

    @Test
    fun timeConversionRoundTripsAndPadsSingleDigitHours() {
        assertEquals("05:30", TasksApi.localTimeToUtc("7:30", paris, now))
        assertEquals("07:30", TasksApi.utcTimeToLocal("5:30", paris, now).toString())
        // Winter offset is +1: the same wall time converts differently.
        assertEquals("06:30", TasksApi.localTimeToUtc("07:30", paris, Instant.parse("2026-12-01T12:00:00Z")))
    }

    @Test
    fun relativeTimeMatchesTheWeb() {
        fun r(iso: String?) = TasksApi.relativeTime(iso, now)
        assertEquals("just now", r("2026-10-09T11:59:40Z"))
        assertEquals("in a moment", r("2026-10-09T12:00:30Z"))
        assertEquals("in 5m", r("2026-10-09T12:05:00Z"))
        assertEquals("10m ago", r("2026-10-09T11:50:00Z"))
        assertEquals("in 18h", r("2026-10-10T06:00:00Z"))
        assertEquals("3d ago", r("2026-10-06T12:00:00Z"))
        assertEquals("", r(null))
        assertEquals("", r("garbage"))
    }

    // -- body checks ------------------------------------------------------

    @Test
    fun checkBodyCatchesHttp200Failures() {
        TasksApi.checkBody("""{"ok":true}""")
        TasksApi.checkBody("""{"tasks":[]}""")
        assertEquals("boom", errorOf { TasksApi.checkBody("""{"error":"boom"}""") }.message)
        assertEquals("nope", errorOf { TasksApi.checkBody("""{"success":false,"message":"nope"}""") }.message)
        assertEquals("Request failed", errorOf { TasksApi.checkBody("""{"ok":false}""") }.message)
        assertEquals("Unexpected response", errorOf { TasksApi.checkBody("<html>") }.message)
    }

    @Test
    fun draftSummaryReadsBack() {
        val d = TaskDraft("research", "n", "p", "weekly", "07:00", 0, null, null, "email")
        assertEquals("Research · Weekly on Monday at 07:00 · result by email", draftSummary(d))
        assertEquals("Prompt · Cron: 0 7 * * 1-5 (UTC)", draftSummary(d.copy(taskType = "llm", schedule = "cron", cronExpression = "0 7 * * 1-5", outputTarget = "session")))
        assertEquals("Prompt · Monthly on the 1st at 09:00", draftSummary(d.copy(taskType = "llm", schedule = "monthly", scheduledDay = 1, scheduledTime = null, outputTarget = null)))
    }
}
