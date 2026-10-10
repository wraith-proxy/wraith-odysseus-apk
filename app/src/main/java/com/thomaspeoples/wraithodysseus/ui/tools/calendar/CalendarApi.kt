package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** An item of GET /api/calendar/calendars. [color] is a hex string or empty. */
data class CalCalendar(val href: String, val name: String, val color: String, val source: String)

/**
 * One event of GET /api/calendar/events, already on this phone's clock.
 *
 * [start] and [end] are local date-times. For an all-day event they are
 * midnights and [end] is the exclusive end (a one-day event ends the next
 * midnight), which is how the server stores them; a zero-length all-day row,
 * which the web treats as one day, is widened to that here. A recurring
 * series arrives as one row per occurrence whose [uid] is "{series}::{when}".
 */
data class CalEvent(
    val uid: String,
    val seriesUid: String,
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val description: String,
    val location: String,
    val rrule: String,
    val calendarHref: String,
    val calendarName: String,
    val color: String,
    val recurring: Boolean,
)

/** What the editor saves. For an all-day event [end] is the exclusive end, as in [CalEvent]. */
data class EventDraft(
    val summary: String,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val description: String,
    val location: String,
    val rrule: String,
    /** Only used when creating: the server ignores it on an update. */
    val calendarHref: String? = null,
)

/** The reply of POST /api/calendar/quick-parse, as the server wrote it (naive local text). */
data class QuickParse(
    val summary: String,
    val dtstart: String,
    val dtend: String,
    val allDay: Boolean,
    val location: String,
    val description: String,
)

/** The events of a range; [truncated] when the server stopped expanding a series at its limit. */
data class EventPage(val events: List<CalEvent>, val truncated: Boolean)

/**
 * The calendar routes (routes/calendar_routes.py). Errors are real HTTP
 * errors (FastAPI `detail`) except quick-parse, which answers 200 with
 * ok=false; [checkBody] covers both. [zone] is the clock the user lives by:
 * timed events are sent with its offset and read back in it.
 */
class CalendarApi(private val http: Http, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** GET /calendars. The server creates the default calendar on first call. */
    suspend fun calendars(): List<CalCalendar> {
        val json = checkBody(http.call(Request.Builder().url(calUrl("calendars").build()).build()))
        val list = json.optJSONArray("calendars") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val href = o.str("href").ifBlank { return@mapNotNull null }
            CalCalendar(href, o.str("name"), o.str("color"), o.str("source"))
        }
    }

    /**
     * GET /events for [from] up to [toExclusive], as the web asks: two plain
     * dates. The server compares them with its stored (UTC) times, so the
     * window is widened by a day each side and the screen trims it by local
     * dates; an event near midnight is then never lost to the offset.
     */
    suspend fun events(from: LocalDate, toExclusive: LocalDate): EventPage {
        val url = calUrl("events").addQueryParameter("start", from.minusDays(1).toString())
            .addQueryParameter("end", toExclusive.plusDays(1).toString()).build()
        val json = checkBody(http.call(Request.Builder().url(url).build()))
        val list = json.optJSONArray("events") ?: JSONArray()
        val events = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { parseEventOrNull(it, zone) } }
        return EventPage(events, json.optBoolean("truncated"))
    }

    /** GET /events/{uid}: for an occurrence uid the server answers with the series row. */
    suspend fun event(uid: String): CalEvent =
        parseReply(checkBody(http.call(Request.Builder().url(calUrl("events", uid).build()).build())))

    /** POST /events. Never retried: a lost answer must not make two events. */
    suspend fun create(draft: EventDraft): CalEvent {
        val body = draftJson(draft, zone, isNew = true).toString().toRequestBody(JSON)
        return parseReply(checkBody(http.call(Request.Builder().url(calUrl("events").build()).post(body).build(), retry = false)))
    }

    /**
     * PUT /events/{uid}. The server has no per-occurrence edit: whatever the
     * uid, it changes the series row, so the screen edits the series (see
     * [event]) and says so.
     */
    suspend fun update(uid: String, draft: EventDraft): CalEvent {
        val body = draftJson(draft, zone, isNew = false).toString().toRequestBody(JSON)
        return parseReply(checkBody(http.call(Request.Builder().url(calUrl("events", uid).build()).put(body).build())))
    }

    /**
     * DELETE /events/{uid}. With [occurrenceOnly] the web adds
     * `?scope=occurrence` and the server hides just that occurrence; without
     * it the uid (even an occurrence's) removes the whole series. A 404 means
     * it is already gone, which is the state wanted, as on the web.
     */
    suspend fun delete(uid: String, occurrenceOnly: Boolean) {
        val url = calUrl("events", uid).apply { if (occurrenceOnly) addQueryParameter("scope", "occurrence") }.build()
        try {
            checkBody(http.call(Request.Builder().url(url).delete().build()))
        } catch (e: ApiException) {
            if (e.status != 404) throw e
        }
    }

    /** POST /quick-parse: the server's model reads [text] against "now" in the user's time zone. Never retried. */
    suspend fun quickParse(text: String): QuickParse {
        val offsetMinutes = zone.rules.getOffset(Instant.now()).totalSeconds / 60
        val body = JSONObject().put("text", text).put("tz", zone.id).put("tz_offset", offsetMinutes).toString().toRequestBody(JSON)
        val json = checkBody(http.call(Request.Builder().url(calUrl("quick-parse").build()).post(body).build(), retry = false))
        val e = json.optJSONObject("event") ?: throw ApiException(200, "Unexpected response")
        return QuickParse(e.str("summary"), e.str("dtstart"), e.str("dtend"), e.optBoolean("all_day"), e.str("location"), e.str("description"))
    }

    /** Segments are encoded one by one, so a uid can never add a path. */
    private fun calUrl(vararg segments: String): HttpUrl.Builder =
        http.url("/api/calendar").newBuilder().apply { segments.forEach { addPathSegment(it) } }

    private fun parseReply(json: JSONObject): CalEvent {
        val o = json.optJSONObject("event") ?: throw ApiException(200, "Unexpected response")
        return parseEventOrNull(o, zone) ?: throw ApiException(200, "Unexpected response")
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The 200-with-an-error shapes: `error`, `success:false`, `ok:false`. */
        internal fun checkBody(body: String): JSONObject {
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            if (error != null) throw ApiException(200, error)
            if (json.opt("success") == false || json.opt("ok") == false) throw ApiException(200, "Request failed")
            return json
        }

        private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

        /** An event row, or null when its times cannot be read (one bad row must not blank the calendar). */
        internal fun parseEventOrNull(o: JSONObject, zone: ZoneId): CalEvent? = try {
            parseEvent(o, zone)
        } catch (_: JSONException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }

        internal fun parseEvent(o: JSONObject, zone: ZoneId): CalEvent {
            val uid = o.getString("uid")
            val allDay = o.optBoolean("all_day")
            val rawStart = o.getString("dtstart")
            val start = parseWhen(rawStart, allDay, zone)
            var end = parseWhen(o.str("dtend").ifBlank { rawStart }, allDay, zone)
            // Zero-length all-day rows are one day on the web; a timed end before its start is clamped.
            if (allDay && !end.isAfter(start)) end = start.plusDays(1)
            if (!allDay && end.isBefore(start)) end = start
            val rrule = o.str("rrule")
            return CalEvent(
                uid = uid,
                seriesUid = o.str("series_uid").ifBlank { uid.substringBefore("::") },
                summary = o.str("summary"),
                start = start,
                end = end,
                allDay = allDay,
                description = o.str("description"),
                location = o.str("location"),
                rrule = rrule,
                calendarHref = o.str("calendar_href"),
                calendarName = o.str("calendar"),
                color = o.str("color"),
                recurring = o.optBoolean("is_recurrence") || "::" in uid || rrule.isNotBlank(),
            )
        }

        private val OFFSET_TAIL = Regex("([Zz]|[+-]\\d{2}:?\\d{2})$")

        /**
         * The server writes an all-day event as a bare date, a timed event
         * stored as UTC with a trailing Z, and an older timed event as naive
         * text that the web reads as local wall-clock time. All three come
         * out on [zone]'s clock.
         */
        internal fun parseWhen(text: String, allDay: Boolean, zone: ZoneId): LocalDateTime {
            val s = text.trim()
            if (allDay || s.length == 10) return LocalDate.parse(s.take(10)).atStartOfDay()
            if (OFFSET_TAIL.containsMatchIn(s)) {
                val withColon = s.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2").replace(Regex("z$"), "Z")
                return OffsetDateTime.parse(withColon).atZoneSameInstant(zone).toLocalDateTime()
            }
            return LocalDateTime.parse(s.replace(' ', 'T'))
        }

        /** "+01:00" for [at] on [zone]: the web appends the browser's offset, so the server stores the true instant. */
        internal fun offsetText(zone: ZoneId, at: LocalDateTime): String {
            val total = zone.rules.getOffset(at).totalSeconds / 60
            val abs = Math.abs(total)
            return "%s%02d:%02d".format(if (total >= 0) "+" else "-", abs / 60, abs % 60)
        }

        /**
         * The save body, as the web builds it: all-day events are bare dates,
         * timed ones local time with the offset. A new event names its
         * calendar; an update never does (the server ignores it). The
         * reminder field is left out, so the server keeps any reminder.
         */
        internal fun draftJson(d: EventDraft, zone: ZoneId, isNew: Boolean): JSONObject = JSONObject().apply {
            put("summary", d.summary)
            if (d.allDay) {
                put("dtstart", d.start.toLocalDate().toString())
                put("dtend", d.end.toLocalDate().toString())
            } else {
                put("dtstart", timedText(d.start, zone))
                put("dtend", timedText(d.end, zone))
            }
            put("all_day", d.allDay)
            put("description", d.description)
            put("location", d.location)
            put("rrule", d.rrule)
            if (isNew) d.calendarHref?.let { put("calendar_href", it) }
        }

        /** Minutes are all the form has; truncating makes the text always "yyyy-MM-ddTHH:mm" plus ":00". */
        private fun timedText(t: LocalDateTime, zone: ZoneId): String =
            "${t.truncatedTo(ChronoUnit.MINUTES)}:00" + offsetText(zone, t)
    }
}
