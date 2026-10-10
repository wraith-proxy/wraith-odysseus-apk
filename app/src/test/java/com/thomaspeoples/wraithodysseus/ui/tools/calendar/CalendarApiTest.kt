package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.time.LocalDate
import java.time.LocalDateTime
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Every call CalendarApi makes: path, method, query, body, and the answers the server gives. */
class CalendarApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val paris = ZoneId.of("Europe/Paris")
    private val server = MockWebServer()
    private lateinit var api: CalendarApi

    @Before
    fun setUp() {
        server.start()
        api = CalendarApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))), paris)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun draft(allDay: Boolean = false) =
        if (allDay) EventDraft("Trip", true, LocalDate.of(2026, 10, 20).atStartOfDay(), LocalDate.of(2026, 10, 23).atStartOfDay(), "n", "Rome", "", "cal-1")
        else EventDraft("Lunch", false, LocalDateTime.of(2026, 10, 16, 12, 0), LocalDateTime.of(2026, 10, 16, 13, 30), "d", "Cafe", "FREQ=WEEKLY", "cal-1")

    @Test
    fun calendarsGetsTheListWithTheCookie() = runBlocking {
        reply(fixture("calendar_calendars.json"))
        val cals = api.calendars()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/calendar/calendars", req.requestUrl!!.encodedPath)
        assertEquals("abc123", req.getHeader("Cookie")!!.substringAfter('='))
        assertEquals(listOf("Personal", "Work"), cals.map { it.name })
        assertEquals("cal-2", cals[1].href)
    }

    @Test
    fun eventsAsksForWidenedPlainDatesAndParsesTheRows() = runBlocking {
        reply(fixture("calendar_events.json"))
        val page = api.events(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/calendar/events", url.encodedPath)
        assertEquals("2026-10-11", url.queryParameter("start"))
        assertEquals("2026-10-20", url.queryParameter("end"))
        assertTrue(page.truncated)
        // The row with no times is dropped, the other six are read.
        assertEquals(6, page.events.size)
    }

    @Test
    fun allDayEventIsDatesWithAnExclusiveEnd() {
        val e = parse("""{"uid":"a","summary":"H","dtstart":"2026-10-12","dtend":"2026-10-14","all_day":true}""")
        assertTrue(e.allDay)
        assertEquals(LocalDate.of(2026, 10, 12).atStartOfDay(), e.start)
        assertEquals(LocalDate.of(2026, 10, 14).atStartOfDay(), e.end)
        assertEquals(LocalDate.of(2026, 10, 13), e.lastDay())
    }

    @Test
    fun zeroLengthAllDayIsOneDay() {
        val e = parse("""{"uid":"a","summary":"H","dtstart":"2026-10-15","dtend":"2026-10-15","all_day":true,"description":null}""")
        assertEquals(LocalDate.of(2026, 10, 16).atStartOfDay(), e.end)
        assertEquals("", e.description)
    }

    @Test
    fun utcTimesAreShownOnTheLocalClockAndNaiveOnesAsWritten() {
        val utc = parse("""{"uid":"a","summary":"D","dtstart":"2026-10-13T08:30:00Z","dtend":"2026-10-13T09:30:00Z","all_day":false}""")
        assertEquals(LocalDateTime.of(2026, 10, 13, 10, 30), utc.start)
        assertEquals(LocalDateTime.of(2026, 10, 13, 11, 30), utc.end)
        val naive = parse("""{"uid":"b","summary":"D","dtstart":"2026-10-13T18:00:00","dtend":"2026-10-13T19:00:00","all_day":false}""")
        assertEquals(LocalDateTime.of(2026, 10, 13, 18, 0), naive.start)
        val offset = parse("""{"uid":"c","summary":"D","dtstart":"2026-10-13T08:30:00+0100","dtend":"2026-10-13T09:30:00+01:00","all_day":false}""")
        assertEquals(LocalDateTime.of(2026, 10, 13, 9, 30), offset.start)
    }

    @Test
    fun recurringIsDetectedFromTheUidFlagOrRule() {
        val occ = parse("""{"uid":"ser-1::2026-10-13T09:00","summary":"S","dtstart":"2026-10-13T09:00:00","dtend":"2026-10-13T09:15:00","all_day":false,"is_recurrence":true,"series_uid":"ser-1","rrule":"FREQ=DAILY"}""")
        assertTrue(occ.recurring)
        assertEquals("ser-1", occ.seriesUid)
        assertTrue(parse("""{"uid":"x::y","dtstart":"2026-10-13","all_day":true}""").recurring)
        assertTrue(parse("""{"uid":"x","dtstart":"2026-10-13","all_day":true,"rrule":"FREQ=DAILY"}""").recurring)
        assertFalse(parse("""{"uid":"x","dtstart":"2026-10-13","all_day":true}""").recurring)
    }

    @Test
    fun createPostsTimedBodyWithOffsetCalendarAndNeverRetries() = runBlocking {
        reply(fixture("calendar_event_created.json"))
        val made = api.create(draft())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/calendar/events", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Lunch", body.getString("summary"))
        assertEquals("2026-10-16T12:00:00+02:00", body.getString("dtstart"))
        assertEquals("2026-10-16T13:30:00+02:00", body.getString("dtend"))
        assertFalse(body.getBoolean("all_day"))
        assertEquals("d", body.getString("description"))
        assertEquals("Cafe", body.getString("location"))
        assertEquals("FREQ=WEEKLY", body.getString("rrule"))
        assertEquals("cal-1", body.getString("calendar_href"))
        assertFalse(body.has("reminder_minutes"))
        assertEquals("new-1", made.uid)
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 0), made.start)
    }

    @Test
    fun createAllDaySendsBareDatesWithTheExclusiveEnd() = runBlocking {
        reply(fixture("calendar_event_created.json"))
        api.create(draft(allDay = true))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("2026-10-20", body.getString("dtstart"))
        assertEquals("2026-10-23", body.getString("dtend"))
        assertTrue(body.getBoolean("all_day"))
    }

    @Test
    fun offsetFollowsTheDateAcrossDaylightSaving() {
        assertEquals("+02:00", CalendarApi.offsetText(paris, LocalDateTime.of(2026, 10, 24, 12, 0)))
        assertEquals("+01:00", CalendarApi.offsetText(paris, LocalDateTime.of(2026, 10, 26, 12, 0)))
        assertEquals("+00:00", CalendarApi.offsetText(ZoneId.of("UTC"), LocalDateTime.of(2026, 10, 26, 12, 0)))
        assertEquals("-05:30", CalendarApi.offsetText(ZoneId.of("-05:30"), LocalDateTime.of(2026, 10, 26, 12, 0)))
    }

    @Test
    fun updatePutsToTheUidWithoutACalendarAndAClearedRepeatIsSent() = runBlocking {
        reply(fixture("calendar_event_created.json"))
        api.update("ser-1", draft().copy(rrule = ""))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/calendar/events/ser-1", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.has("calendar_href"))
        assertEquals("", body.getString("rrule"))
        assertEquals("2026-10-16T12:00:00+02:00", body.getString("dtstart"))
    }

    @Test
    fun eventGetsTheSeriesRowForAnOccurrenceUid() = runBlocking {
        reply("""{"event":{"uid":"ser-1","summary":"S","dtstart":"2026-10-01T09:00:00Z","dtend":"2026-10-01T09:15:00Z","all_day":false,"rrule":"FREQ=DAILY"}}""")
        val e = api.event("ser-1::2026-10-13T09:00")
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals(listOf("api", "calendar", "events", "ser-1::2026-10-13T09:00"), req.requestUrl!!.pathSegments)
        assertEquals(LocalDateTime.of(2026, 10, 1, 11, 0), e.start)
    }

    @Test
    fun deleteSeriesSendsNoScope() = runBlocking {
        reply("""{"ok":true}""")
        api.delete("u1", occurrenceOnly = false)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/calendar/events/u1", req.requestUrl!!.encodedPath)
        assertNull(req.requestUrl!!.query)
    }

    @Test
    fun deleteOccurrenceSendsScopeOccurrenceOnTheCompoundUid() = runBlocking {
        reply("""{"ok":true,"scope":"occurrence","exdate":"2026-10-13T09:00"}""")
        api.delete("ser-1::2026-10-13T09:00", occurrenceOnly = true)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals(listOf("api", "calendar", "events", "ser-1::2026-10-13T09:00"), req.requestUrl!!.pathSegments)
        assertEquals("occurrence", req.requestUrl!!.queryParameter("scope"))
    }

    @Test
    fun deleteOfAnEventAlreadyGoneIsFine() = runBlocking {
        reply("""{"detail":"Event not found"}""", 404)
        api.delete("gone", occurrenceOnly = false)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun deleteFailureOtherThanNotFoundIsReported() = runBlocking {
        reply("""{"detail":"Failed to delete event"}""", 500)
        try {
            api.delete("u1", occurrenceOnly = false)
            fail()
        } catch (e: ApiException) {
            assertEquals("Failed to delete event", e.message)
        }
    }

    @Test
    fun quickParseSendsTextZoneAndOffsetAndReadsTheEvent() = runBlocking {
        reply(fixture("calendar_quick_parse.json"))
        val q = api.quickParse("lunch with sara friday 1pm")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/calendar/quick-parse", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("lunch with sara friday 1pm", body.getString("text"))
        assertEquals("Europe/Paris", body.getString("tz"))
        assertEquals(true, body.getInt("tz_offset") in listOf(60, 120))
        assertEquals("Lunch with Sara", q.summary)
        assertEquals("2026-10-16T13:00:00", q.dtstart)
        assertEquals("Downtown", q.location)
    }

    @Test
    fun quickParseFailureIsAnErrorWith200() = runBlocking {
        reply("""{"ok":false,"error":"No LLM endpoint configured"}""")
        try {
            api.quickParse("x")
            fail()
        } catch (e: ApiException) {
            assertEquals("No LLM endpoint configured", e.message)
        }
    }

    @Test
    fun errorBodiesWith200AreCaught() {
        for (body in listOf("""{"error":"nope"}""", """{"success":false}""", """{"ok":false}""", "not json")) {
            try {
                CalendarApi.checkBody(body)
                fail(body)
            } catch (_: ApiException) {
            }
        }
    }

    @Test
    fun quickParseBecomesADraftOrNull() {
        val timed = QuickParse("Lunch", "2026-10-16T13:00:00", "2026-10-16T14:00:00", false, "Downtown", "").toDraft("cal-1")!!
        assertEquals(LocalDateTime.of(2026, 10, 16, 13, 0), timed.start)
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 0), timed.end)
        assertEquals("cal-1", timed.calendarHref)
        val noEnd = QuickParse("Lunch", "2026-10-16T13:00:00", "", false, "", "").toDraft(null)!!
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 0), noEnd.end)
        val allDay = QuickParse("Trip", "2026-10-16", "2026-10-18", true, "", "").toDraft(null)!!
        assertEquals(LocalDate.of(2026, 10, 19).atStartOfDay(), allDay.end)
        val oneDay = QuickParse("Trip", "2026-10-16", "2026-10-16", true, "", "").toDraft(null)!!
        assertEquals(LocalDate.of(2026, 10, 17).atStartOfDay(), oneDay.end)
        assertNull(QuickParse("Bad", "tomorrow", "", false, "", "").toDraft(null))
    }

    private fun parse(json: String) = CalendarApi.parseEvent(JSONObject(json), paris)
}
