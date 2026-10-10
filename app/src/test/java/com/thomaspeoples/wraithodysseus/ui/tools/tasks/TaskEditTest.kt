package com.thomaspeoples.wraithodysseus.ui.tools.tasks

import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.time.Instant
import java.time.ZoneId
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

/** The Edit flow against the web's save (static/js/tasks.js _showForm, routes/task/task_routes.py update_task). */
class TaskEditTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

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

    private fun task(json: String) = TasksApi.parseTask(JSONObject(json))!!

    private val daily = task("""{"id":"t1","name":"Morning news","prompt":"Summarise","task_type":"research","schedule":"daily","scheduled_time":"06:00","trigger_type":"schedule","status":"active"}""")
    private val weekly = task("""{"id":"t2","name":"Review","prompt":"Review the week","task_type":"llm","schedule":"weekly","scheduled_time":"15:30","scheduled_day":4,"trigger_type":"schedule","status":"paused"}""")
    private val action = task("""{"id":"t3","name":"Memory tidy","prompt":null,"task_type":"action","action":"memory_cleanup","schedule":"monthly","scheduled_time":"01:00","scheduled_day":1,"trigger_type":"schedule","status":"active","is_builtin":true}""")
    private val event = task("""{"id":"t4","name":"On session","prompt":"Tag it","task_type":"llm","trigger_type":"event","trigger_event":"session_created","trigger_count":3,"status":"active"}""")
    private val webhook = task("""{"id":"t5","name":"Hook","prompt":"Handle","task_type":"llm","trigger_type":"webhook","status":"active"}""")
    private val once = task("""{"id":"t6","name":"Once","prompt":"Do","task_type":"llm","schedule":"once","scheduled_date":"2026-10-12T05:30:00Z","trigger_type":"schedule","status":"active"}""")

    private fun form(t: Task) = TaskForm.of(t, paris, now)
    private fun body(t: Task, f: TaskForm = form(t)) = TasksApi.updateBody(t, f, paris, now)
    private fun problem(t: Task, f: TaskForm = form(t)) = TasksApi.editProblem(t, f, paris, now)

    // -- prefill ----------------------------------------------------------

    @Test
    fun prefillShowsUtcTimesAsLocal() {
        val f = form(daily)
        assertEquals("08:00", f.time)
        assertEquals("daily", f.schedule)
        assertEquals("Morning news", f.name)
        assertEquals("Summarise", f.prompt)
        val w = form(weekly)
        assertEquals("17:30", w.time)
        assertEquals("4", w.day)
        assertEquals("3", form(event).count)
        assertEquals("5", form(webhook).count)
    }

    @Test
    fun prefillOfOnceTaskUsesItsDateNotTheStoredTime() {
        val f = form(once)
        assertEquals("once", f.schedule)
        assertEquals("2026-10-12", f.date)
        assertEquals("07:30", f.time)
    }

    @Test
    fun savedFormRoundTripsThroughTheSaver() {
        val f = form(weekly)
        val saved = TaskForm.Saver.run { val scope = androidx.compose.runtime.saveable.SaverScope { true }; scope.save(f) }
        assertEquals(f, TaskForm.Saver.restore(saved!!))
    }

    // -- request bodies ---------------------------------------------------

    @Test
    fun promptTaskBodyConvertsTimeToUtcAndSendsPrompt() {
        val b = body(daily, form(daily).copy(name = " Renamed ", prompt = " New question ", time = "07:15"))
        assertEquals("Renamed", b.getString("name"))
        assertEquals("New question", b.getString("prompt"))
        assertEquals("research", b.getString("task_type"))
        assertEquals("schedule", b.getString("trigger_type"))
        assertEquals("daily", b.getString("schedule"))
        assertEquals("05:15", b.getString("scheduled_time"))
        assertFalse(b.has("scheduled_day"))
        assertFalse(b.has("cron_expression"))
        assertFalse(b.has("scheduled_date"))
        // What the form does not show is not sent, so the server keeps it.
        for (k in listOf("action", "model", "endpoint_url", "then_task_id", "character_id", "notifications_enabled", "output_target")) assertFalse(k, b.has(k))
    }

    @Test
    fun blankNameIsLeftOutLikeTheWeb() {
        assertFalse(body(daily, form(daily).copy(name = "  ")).has("name"))
    }

    @Test
    fun weeklyAndMonthlyBodies() {
        val w = body(weekly, form(weekly).copy(day = "6", time = "09:00"))
        assertEquals("weekly", w.getString("schedule"))
        assertEquals(6, w.getInt("scheduled_day"))
        assertEquals("07:00", w.getString("scheduled_time"))
        // A monthly day left in the box becomes a weekday of Monday, as the web's select would.
        assertEquals(0, body(weekly, form(weekly).copy(day = "15")).getInt("scheduled_day"))
        val m = body(weekly, form(weekly).copy(schedule = "monthly", day = "22"))
        assertEquals("monthly", m.getString("schedule"))
        assertEquals(22, m.getInt("scheduled_day"))
    }

    @Test
    fun cronBodySendsExpressionAndNoTime() {
        val b = body(daily, form(daily).copy(schedule = "cron", cron = " 0 7 * * 1-5 "))
        assertEquals("cron", b.getString("schedule"))
        assertEquals("0 7 * * 1-5", b.getString("cron_expression"))
        assertFalse(b.has("scheduled_time"))
    }

    @Test
    fun onceBodySendsUtcInstantAndTime() {
        val b = body(once, form(once).copy(date = "2026-10-20", time = "18:45"))
        assertEquals("once", b.getString("schedule"))
        assertEquals("2026-10-20T16:45:00Z", b.getString("scheduled_date"))
        assertEquals("16:45", b.getString("scheduled_time"))
        assertFalse(b.has("scheduled_day"))
        // Winter offset applies to the date picked, the time of day to today's offset, as the web does.
        val w = TasksApi.updateBody(once, form(once).copy(date = "2026-12-20", time = "18:45"), paris, now)
        assertEquals("2026-12-20T17:45:00Z", w.getString("scheduled_date"))
        assertEquals("16:45", w.getString("scheduled_time"))
    }

    @Test
    fun actionTaskSendsOnlyNameAndSchedule() {
        val b = body(action, form(action).copy(name = "Tidy", time = "04:00", day = "15"))
        assertEquals("Tidy", b.getString("name"))
        assertEquals("monthly", b.getString("schedule"))
        assertEquals("02:00", b.getString("scheduled_time"))
        assertEquals(15, b.getInt("scheduled_day"))
        assertFalse(b.has("prompt"))
        assertFalse(b.has("task_type"))
        assertFalse(b.has("action"))
    }

    @Test
    fun eventTaskSendsEventAndCountButNoSchedule() {
        val b = body(event, form(event).copy(count = "10"))
        assertEquals("event", b.getString("trigger_type"))
        assertEquals("session_created", b.getString("trigger_event"))
        assertEquals(10, b.getInt("trigger_count"))
        assertEquals("Tag it", b.getString("prompt"))
        assertFalse(b.has("schedule"))
        assertFalse(b.has("scheduled_time"))
    }

    @Test
    fun webhookTaskSendsNameAndPromptOnly() {
        val b = body(webhook, form(webhook).copy(prompt = "Handle it"))
        assertEquals("webhook", b.getString("trigger_type"))
        assertEquals("Handle it", b.getString("prompt"))
        assertFalse(b.has("schedule"))
        assertFalse(b.has("trigger_count"))
    }

    // -- validation -------------------------------------------------------

    @Test
    fun validationMatchesTheWebForm() {
        assertNull(problem(daily))
        assertEquals("Prompt is required", problem(daily, form(daily).copy(prompt = "  ")))
        assertNull(problem(action, form(action).copy(prompt = "")))
        assertEquals("Cron expression is required", problem(daily, form(daily).copy(schedule = "cron", cron = " ")))
        assertNull(problem(daily, form(daily).copy(schedule = "cron", cron = "*/5 * * * *")))
        assertTrue(problem(weekly, form(weekly).copy(schedule = "monthly", day = "")) != null)
        assertTrue(problem(weekly, form(weekly).copy(schedule = "monthly", day = "32")) != null)
        assertNull(problem(weekly, form(weekly).copy(schedule = "monthly", day = "31")))
        assertTrue(problem(event, form(event).copy(count = "0")) != null)
        assertTrue(problem(event, form(event).copy(count = "1001")) != null)
        assertTrue(problem(event, form(event).copy(count = "")) != null)
        assertNull(problem(event, form(event).copy(count = "1000")))
        assertNull(problem(webhook))
    }

    @Test
    fun onceUsesOnceProblemForAChangedDate() {
        // Untouched: fine, even though it is in the past by then.
        assertNull(problem(once))
        val later = Instant.parse("2026-11-01T00:00:00Z")
        assertNull(TasksApi.editProblem(once, form(once), paris, later))
        // Moved into the past, or in from another schedule: refused as the draft step does.
        assertTrue(problem(once, form(once).copy(date = "2026-10-09", time = "14:00")) != null)
        assertTrue(problem(once, form(once).copy(date = "2026-10-09", time = "14:01")) == null)
        assertTrue(problem(daily, form(daily).copy(schedule = "once", date = "2001-01-01")) != null)
        assertNull(problem(daily, form(daily).copy(schedule = "once", date = "2999-01-01")))
        assertTrue(problem(daily, form(daily).copy(schedule = "once", date = "garbage")) != null)
        assertEquals(
            TasksApi.onceProblem("once", "2001-01-01T08:00", paris, now),
            problem(daily, form(daily).copy(schedule = "once", date = "2001-01-01", time = "08:00")),
        )
    }

    // -- who gets Edit ----------------------------------------------------

    @Test
    fun everyKindIsEditableExceptAdminActionsForANonAdmin() {
        for (t in listOf(daily, weekly, action, event, webhook, once)) {
            assertTrue(t.id, t.canEdit(isAdmin = false))
            assertTrue(t.id, t.canEdit(isAdmin = true))
        }
        for (name in listOf("run_local", "run_script", "ssh_command", "cookbook_serve")) {
            val t = task("""{"id":"x","task_type":"action","action":"$name","schedule":"daily","trigger_type":"schedule"}""")
            assertFalse(name, t.canEdit(isAdmin = false))
            assertTrue(name, t.canEdit(isAdmin = true))
        }
        // The policy is about action tasks only.
        assertTrue(task("""{"id":"x","task_type":"llm","action":"run_local"}""").canEdit(isAdmin = false))
    }

    // -- the call ---------------------------------------------------------

    @Test
    fun updatePutsTheBodyAndParsesTheSavedTask() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"t1","name":"Renamed","prompt":"p","task_type":"research","schedule":"daily","scheduled_time":"05:15","trigger_type":"schedule","status":"active"}"""))
        val saved = api.update("t1", body(daily, form(daily).copy(name = "Renamed", time = "07:15")))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/tasks/t1", req.path)
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("Renamed", sent.getString("name"))
        assertEquals("05:15", sent.getString("scheduled_time"))
        assertEquals("Renamed", saved.name)
    }

    @Test
    fun updateErrorsAreShown() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Invalid cron expression"}"""))
        val e = runBlocking { runCatching { api.update("t1", JSONObject()) }.exceptionOrNull() }
        assertEquals("Invalid cron expression", e?.message)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Action 'run_local' requires admin privileges"}"""))
        val e2 = runBlocking { runCatching { api.update("t1", JSONObject()) }.exceptionOrNull() }
        assertEquals("Action 'run_local' requires admin privileges", e2?.message)
    }
}
