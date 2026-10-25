package com.thomaspeoples.wraithodysseus.demo

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val zone: ZoneId = ZoneId.systemDefault()
private val nowInstant: Instant = Instant.now()
private val today: LocalDate = LocalDate.now(zone)

/** Server-style UTC instant, e.g. 2026-10-25T10:00:00Z, [hours] before now. */
private val ago: (Long) -> String = { hours ->
    DateTimeFormatter.ISO_INSTANT.format(nowInstant.minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
}

/** The same, [hours] after now. */
private val ahead: (Long) -> String = { hours ->
    DateTimeFormatter.ISO_INSTANT.format(nowInstant.plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
}

/** A timed event bound: local [hour]:[minute] on today plus [days], as a UTC instant. */
private val at: (Long, Int, Int) -> String = { days, hour, minute ->
    DateTimeFormatter.ISO_INSTANT.format(
        today.plusDays(days).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant(),
    )
}

/** A bare date [days] from today. */
private val day: (Long) -> String = { days -> today.plusDays(days).toString() }

internal val demoPlanner: Map<String, String> = mapOf(
    "GET /api/notes" to """
{"notes":[
{"id":"n1","title":"Weekend trip","content":"Book the train for Saturday morning and check the hotel's late check-in.","items":[],"note_type":"note","color":"blue","label":"travel personal","pinned":true,"archived":false,"due_date":"${day(3)}","repeat":"none","source":"user","sort_order":5,"image_url":null,"created_at":"${ago(70)}","updated_at":"${ago(2)}"},
{"id":"n2","title":"Groceries","content":"","items":[{"id":"i1","text":"Oat milk","done":true,"indent":0},{"id":"i2","text":"Sourdough","done":false,"indent":0},{"id":"i3","text":"Tomatoes","done":false,"indent":0},{"id":"i4","text":"Cherry","done":false,"indent":1},{"id":"i5","text":"Coffee beans","done":true,"indent":0}],"note_type":"todo","color":"green","label":"home","pinned":false,"archived":false,"due_date":null,"repeat":"none","source":"user","sort_order":4,"image_url":null,"created_at":"${ago(30)}","updated_at":"${ago(5)}"},
{"id":"n3","title":"Meeting notes: Q4 planning","content":"Focus on onboarding, fewer features. Draft the roadmap by Friday and share it with the team at demo@example.com.","items":[],"note_type":"note","color":"yellow","label":"work","pinned":false,"archived":false,"due_date":null,"repeat":"none","source":"user","sort_order":3,"image_url":null,"created_at":"${ago(26)}","updated_at":"${ago(26)}"},
{"id":"n4","title":"Book ideas","content":"The Overstory, Piranesi, A Psalm for the Wild-Built.","items":[],"note_type":"note","color":"","label":"reading","pinned":false,"archived":false,"due_date":null,"repeat":"none","source":"user","sort_order":2,"image_url":null,"created_at":"${ago(120)}","updated_at":"${ago(100)}"},
{"id":"n5","title":"Wi-Fi and door codes","content":"Guest network is on the fridge card. Ask the landlord for the spare key.","items":[],"note_type":"note","color":"red","label":"home","pinned":false,"archived":false,"due_date":null,"repeat":"none","source":"user","sort_order":1,"image_url":null,"created_at":"${ago(300)}","updated_at":"${ago(240)}"}
]}
""".trimIndent(),
    "GET /api/calendar/calendars" to """
{"calendars":[
{"href":"/calendars/demo/personal/","name":"Personal","color":"#4f8cff","source":"local"},
{"href":"/calendars/demo/work/","name":"Work","color":"#2ea36b","source":"local"}
]}
""".trimIndent(),
    "GET /api/calendar/events" to """
{"truncated":false,"events":[
{"uid":"ev1","series_uid":"ev1","summary":"Team standup","dtstart":"${at(0, 10, 0)}","dtend":"${at(0, 10, 30)}","all_day":false,"description":"Quick sync on the week.","location":"Meeting room 2","rrule":"","calendar_href":"/calendars/demo/work/","calendar":"Work","color":"#2ea36b","is_recurrence":false},
{"uid":"ev2","series_uid":"ev2","summary":"Lunch with Sam","dtstart":"${at(1, 12, 30)}","dtend":"${at(1, 13, 30)}","all_day":false,"description":"","location":"Market Street cafe","rrule":"","calendar_href":"/calendars/demo/personal/","calendar":"Personal","color":"#4f8cff","is_recurrence":false},
{"uid":"ev3","series_uid":"ev3","summary":"Design review","dtstart":"${at(2, 15, 0)}","dtend":"${at(2, 16, 0)}","all_day":false,"description":"Walk through the new onboarding screens.","location":"Video call","rrule":"","calendar_href":"/calendars/demo/work/","calendar":"Work","color":"#2ea36b","is_recurrence":false},
{"uid":"ev4","series_uid":"ev4","summary":"Weekend trip","dtstart":"${day(4)}","dtend":"${day(6)}","all_day":true,"description":"Train leaves at 9:15.","location":"","rrule":"","calendar_href":"/calendars/demo/personal/","calendar":"Personal","color":"#4f8cff","is_recurrence":false},
{"uid":"ev5","series_uid":"ev5","summary":"Weekly planning","dtstart":"${at(8, 9, 0)}","dtend":"${at(8, 9, 45)}","all_day":false,"description":"Plan the week ahead.","location":"","rrule":"FREQ=WEEKLY","calendar_href":"/calendars/demo/work/","calendar":"Work","color":"#2ea36b","is_recurrence":false}
]}
""".trimIndent(),
    "GET /api/calendar/events/ev1" to """
{"event":{"uid":"ev1","series_uid":"ev1","summary":"Team standup","dtstart":"${at(0, 10, 0)}","dtend":"${at(0, 10, 30)}","all_day":false,"description":"Quick sync on the week.","location":"Meeting room 2","rrule":"","calendar_href":"/calendars/demo/work/","calendar":"Work","color":"#2ea36b","is_recurrence":false}}
""".trimIndent(),
    "GET /api/calendar/events/ev2" to """
{"event":{"uid":"ev2","series_uid":"ev2","summary":"Lunch with Sam","dtstart":"${at(1, 12, 30)}","dtend":"${at(1, 13, 30)}","all_day":false,"description":"","location":"Market Street cafe","rrule":"","calendar_href":"/calendars/demo/personal/","calendar":"Personal","color":"#4f8cff","is_recurrence":false}}
""".trimIndent(),
    "GET /api/calendar/events/ev3" to """
{"event":{"uid":"ev3","series_uid":"ev3","summary":"Design review","dtstart":"${at(2, 15, 0)}","dtend":"${at(2, 16, 0)}","all_day":false,"description":"Walk through the new onboarding screens.","location":"Video call","rrule":"","calendar_href":"/calendars/demo/work/","calendar":"Work","color":"#2ea36b","is_recurrence":false}}
""".trimIndent(),
    "GET /api/calendar/events/ev4" to """
{"event":{"uid":"ev4","series_uid":"ev4","summary":"Weekend trip","dtstart":"${day(4)}","dtend":"${day(6)}","all_day":true,"description":"Train leaves at 9:15.","location":"","rrule":"","calendar_href":"/calendars/demo/personal/","calendar":"Personal","color":"#4f8cff","is_recurrence":false}}
""".trimIndent(),
    "GET /api/calendar/events/ev5" to """
{"event":{"uid":"ev5","series_uid":"ev5","summary":"Weekly planning","dtstart":"${at(8, 9, 0)}","dtend":"${at(8, 9, 45)}","all_day":false,"description":"Plan the week ahead.","location":"","rrule":"FREQ=WEEKLY","calendar_href":"/calendars/demo/work/","calendar":"Work","color":"#2ea36b","is_recurrence":false}}
""".trimIndent(),

    "GET /api/tasks" to """
{"tasks":[
{"id":"t1","name":"Morning briefing","prompt":"Summarise my calendar and unread notes for today in five bullet points.","task_type":"llm","action":null,"schedule":"daily","scheduled_time":"06:30","scheduled_day":null,"scheduled_date":null,"cron_expression":null,"trigger_type":"schedule","trigger_event":null,"trigger_count":null,"next_run":"${ahead(9)}","last_run":"${ago(15)}","status":"active","output_target":"session","run_count":14,"is_builtin":false,"last_run_status":"success","last_run_result":"Three events today, two open checklist items."},
{"id":"t2","name":"Weekly reading digest","prompt":"Research new articles on local-first software and list the best three with links.","task_type":"research","action":null,"schedule":"weekly","scheduled_time":"08:00","scheduled_day":0,"scheduled_date":null,"cron_expression":null,"trigger_type":"schedule","trigger_event":null,"trigger_count":null,"next_run":null,"last_run":"${ago(170)}","status":"paused","output_target":"session","run_count":6,"is_builtin":false,"last_run_status":"success","last_run_result":"Found three articles on sync engines and offline storage."},
{"id":"t3","name":"Memory cleanup","prompt":"","task_type":"action","action":"memory_cleanup","schedule":"monthly","scheduled_time":"02:00","scheduled_day":1,"scheduled_date":null,"cron_expression":null,"trigger_type":"schedule","trigger_event":null,"trigger_count":null,"next_run":"${ahead(200)}","last_run":"${ago(500)}","status":"active","output_target":"session","run_count":3,"is_builtin":true,"last_run_status":"success","last_run_result":"Removed 12 stale entries."}
]}
""".trimIndent(),
    "GET /api/tasks/t1/runs" to """
{"runs":[
{"id":"r1a","status":"success","started_at":"${ago(15)}","finished_at":"${ago(15)}","result":"Three events today, two open checklist items.","error":null,"model":"demo-model"},
{"id":"r1b","status":"success","started_at":"${ago(39)}","finished_at":"${ago(39)}","result":"Two events today, one reminder to book the train.","error":null,"model":"demo-model"}
]}
""".trimIndent(),
    "GET /api/tasks/t2/runs" to """
{"runs":[
{"id":"r2a","status":"success","started_at":"${ago(170)}","finished_at":"${ago(170)}","result":"Found three articles on sync engines and offline storage.","error":null,"model":"demo-model"},
{"id":"r2b","status":"error","started_at":"${ago(338)}","finished_at":"${ago(338)}","result":null,"error":"The search provider did not answer in time.","model":"demo-model"}
]}
""".trimIndent(),
    "GET /api/tasks/t3/runs" to """
{"runs":[
{"id":"r3a","status":"success","started_at":"${ago(500)}","finished_at":"${ago(500)}","result":"Removed 12 stale entries.","error":null,"model":null},
{"id":"r3b","status":"success","started_at":"${ago(1240)}","finished_at":"${ago(1240)}","result":"Removed 4 stale entries.","error":null,"model":null}
]}
""".trimIndent(),
)
