package com.thomaspeoples.wraithodysseus.ui.tools.tasks

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** An item of GET /api/tasks (routes/task/task_routes.py _task_to_dict). */
data class Task(
    val id: String,
    val name: String,
    val prompt: String,
    /** "llm", "research" or "action". */
    val taskType: String,
    /** The built-in action an "action" task runs, e.g. "memory_cleanup"; null for prompt tasks. */
    val action: String?,
    /** "once", "daily", "weekly", "monthly" or "cron"; null for event and webhook tasks. */
    val schedule: String?,
    /** HH:MM in UTC: the server computes schedules in UTC and the web UI converts for display. */
    val scheduledTime: String?,
    val scheduledDay: Int?,
    val scheduledDate: String?,
    val cronExpression: String?,
    /** "schedule", "event" or "webhook". */
    val triggerType: String,
    val triggerEvent: String?,
    val triggerCount: Int?,
    val nextRun: String?,
    val lastRun: String?,
    /** "active", "paused" or "completed". */
    val status: String,
    val outputTarget: String,
    val runCount: Int,
    val isBuiltin: Boolean,
    /** Present only when listed with include_last_run=true and the task has run. */
    val lastRunStatus: String?,
    val lastRunResult: String?,
) {
    /** The web hides "Run now" on a finished one-off. */
    val canRun: Boolean get() = status != "completed"

    /** Prompt and research tasks have a prompt to edit; an action task's prompt is its action's own settings. */
    val editsPrompt: Boolean get() = taskType == "llm" || taskType == "research"

    /**
     * Whether the person may edit this task. The web shows Edit on every
     * task and the server (task_routes.py update_task ->
     * _require_admin_for_task_action) answers 403 for the actions that run
     * code on the host unless the caller is an admin, so those get no Edit
     * rather than a form that cannot be saved.
     */
    fun canEdit(isAdmin: Boolean): Boolean =
        isAdmin || !(taskType == "action" && action in ADMIN_ONLY_ACTIONS)
}

/** src/task_action_policy.py ADMIN_ONLY_TASK_ACTIONS. */
private val ADMIN_ONLY_ACTIONS = setOf("run_local", "run_script", "ssh_command", "cookbook_serve")

/** An item of GET /api/tasks/{id}/runs. */
data class TaskRun(
    val id: String,
    /** "success", "error", "failed", "running", "queued", "skipped" or "aborted". */
    val status: String,
    val startedAt: String?,
    val finishedAt: String?,
    val result: String?,
    val error: String?,
    val model: String?,
) {
    /** The run's text as the web shows it: the result, else the error, else a dash. */
    val text: String get() = result?.takeIf { it.isNotEmpty() } ?: error?.takeIf { it.isNotEmpty() } ?: "—"
    val isError: Boolean get() = status == "error" || status == "failed"
}

/**
 * What POST /api/tasks/parse understood. It is a draft only: nothing is saved
 * until [TasksApi.create]. [scheduledTime] and [cronExpression] are in the
 * phone's local time, as the model was told; [TasksApi.create] converts.
 */
data class TaskDraft(
    val taskType: String,
    val name: String?,
    val prompt: String,
    val schedule: String,
    val scheduledTime: String?,
    val scheduledDay: Int?,
    /** Local "YYYY-MM-DDTHH:MM", for "once". */
    val scheduledDate: String?,
    val cronExpression: String?,
    val outputTarget: String?,
)

/**
 * The scheduled-tasks routes. Web parity notes: the list asks for
 * include_last_run like the web, errors come back as HTTP 200 with
 * success:false on /parse, and /api/tasks/notifications is never called
 * because it deletes what it returns and would steal the web tab's reminders.
 */
class TasksApi(private val http: Http) {

    suspend fun list(): List<Task> {
        val body = get("/api/tasks?include_last_run=true")
        val arr = JSONObject(body).optJSONArray("tasks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parseTask(arr.optJSONObject(it)) }
    }

    suspend fun pause(id: String) = post("/api/tasks/$id/pause")

    suspend fun resume(id: String) = post("/api/tasks/$id/resume")

    /** A 409 means it is already running; the server's text says so. */
    suspend fun runNow(id: String) = post("/api/tasks/$id/run")

    suspend fun delete(id: String) {
        val url = http.url("/api/tasks/$id")
        checkBody(http.call(Request.Builder().url(url).delete().build()))
    }

    /**
     * PUT /api/tasks/{id}, the web's _updateTask. A PUT sets the same fields
     * again, so a dropped connection may be retried. The answer is the task as
     * saved.
     */
    suspend fun update(id: String, body: JSONObject): Task {
        val request = Request.Builder().url(http.url("/api/tasks/$id")).put(json(body.toString())).build()
        val text = http.call(request)
        checkBody(text)
        return parseTask(JSONObject(text)) ?: throw ApiException(200, "Unexpected response")
    }

    suspend fun runs(id: String, limit: Int = 20): List<TaskRun> {
        val arr = JSONObject(get("/api/tasks/$id/runs?limit=$limit")).optJSONArray("runs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parseRun(arr.optJSONObject(it)) }
    }

    /** Asks the server's model to read [description]; a 45s model call on the server side. */
    suspend fun parse(description: String): TaskDraft {
        val body = JSONObject().put("description", description).toString()
        val request = Request.Builder().url(http.url("/api/tasks/parse")).post(json(body)).build()
        val text = http.call(request, readTimeoutSeconds = PARSE_TIMEOUT_S, retry = false)
        return parseDraft(text)
    }

    /** Creates the task a [parse] draft described. Never retried: a repeat would make a second task. */
    suspend fun create(draft: TaskDraft, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): Task {
        val request = Request.Builder().url(http.url("/api/tasks")).post(json(createBody(draft, zone, now).toString())).build()
        val text = http.call(request, retry = false)
        checkBody(text)
        return parseTask(JSONObject(text)) ?: throw ApiException(200, "Unexpected response")
    }

    private suspend fun get(path: String): String {
        val text = http.call(Request.Builder().url(http.url(path)).get().build())
        checkBody(text)
        return text
    }

    private suspend fun post(path: String) {
        val request = Request.Builder().url(http.url(path)).post("".toRequestBody()).build()
        checkBody(http.call(request, retry = false))
    }

    private fun json(body: String) = body.toRequestBody("application/json".toMediaType())

    companion object {
        const val PARSE_TIMEOUT_S = 60L

        /** A 200 that is really a failure: `error`, or `success:false` with a `message`. */
        fun checkBody(body: String) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: throw ApiException(200, "Unexpected response")
            (json.opt("error") as? String)?.takeIf { it.isNotBlank() }?.let { throw ApiException(200, it) }
            if (json.opt("success") == false || json.opt("ok") == false) {
                throw ApiException(200, (json.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "Request failed")
            }
        }

        fun parseTask(o: JSONObject?): Task? {
            o ?: return null
            val id = o.optString("id").ifEmpty { return null }
            return Task(
                id = id,
                name = o.str("name").orEmpty(),
                prompt = o.str("prompt").orEmpty(),
                taskType = o.str("task_type") ?: "llm",
                action = o.str("action"),
                schedule = o.str("schedule"),
                scheduledTime = o.str("scheduled_time"),
                scheduledDay = if (o.isNull("scheduled_day")) null else o.optInt("scheduled_day"),
                scheduledDate = o.str("scheduled_date"),
                cronExpression = o.str("cron_expression"),
                triggerType = o.str("trigger_type") ?: "schedule",
                triggerEvent = o.str("trigger_event"),
                triggerCount = if (o.isNull("trigger_count")) null else o.optInt("trigger_count"),
                nextRun = o.str("next_run"),
                lastRun = o.str("last_run"),
                status = o.str("status").orEmpty(),
                outputTarget = o.str("output_target") ?: "session",
                runCount = o.optInt("run_count"),
                isBuiltin = o.optBoolean("is_builtin"),
                lastRunStatus = o.str("last_run_status"),
                lastRunResult = o.str("last_run_result"),
            )
        }

        fun parseRun(o: JSONObject?): TaskRun? {
            o ?: return null
            val id = o.optString("id").ifEmpty { return null }
            return TaskRun(
                id = id,
                status = o.optString("status"),
                startedAt = o.str("started_at"),
                finishedAt = o.str("finished_at"),
                result = o.str("result"),
                error = o.str("error"),
                model = o.str("model"),
            )
        }

        /** The draft of a success:true /parse answer; success:false is an ApiException with its message. */
        fun parseDraft(body: String): TaskDraft {
            checkBody(body)
            val d = JSONObject(body).optJSONObject("draft") ?: throw ApiException(200, "Could not draft a task")
            val prompt = d.str("prompt") ?: throw ApiException(200, "Could not extract a task instruction")
            return TaskDraft(
                taskType = d.str("task_type") ?: "llm",
                name = d.str("name"),
                prompt = prompt,
                schedule = d.str("schedule") ?: "daily",
                scheduledTime = d.str("scheduled_time"),
                scheduledDay = if (d.has("scheduled_day") && !d.isNull("scheduled_day")) d.optInt("scheduled_day") else null,
                scheduledDate = d.str("scheduled_date"),
                cronExpression = d.str("cron_expression"),
                outputTarget = d.str("output_target"),
            )
        }

        /**
         * The POST /api/tasks body. The model answers in local time but the
         * server's schedules run in UTC, so the web converts the time before
         * saving (_localTimeToUtc) and sends a "once" date as an ISO instant;
         * this does the same. Like the web it leaves the weekday and a cron
         * expression as the model wrote them.
         */
        fun createBody(draft: TaskDraft, zone: ZoneId, now: Instant): JSONObject {
            val o = JSONObject()
                .put("task_type", draft.taskType)
                .put("trigger_type", "schedule")
                .put("prompt", draft.prompt)
                .put("schedule", draft.schedule)
                .put("output_target", draft.outputTarget ?: "session")
            draft.name?.let { o.put("name", it) }
            when (draft.schedule) {
                "cron" -> draft.cronExpression?.let { o.put("cron_expression", it) }
                "once" -> localDateTimeToUtcIso(draft.scheduledDate, zone)?.let { o.put("scheduled_date", it) }
            }
            if (draft.schedule != "cron" && draft.schedule != "once") {
                o.put("scheduled_time", localTimeToUtc(draft.scheduledTime ?: "09:00", zone, now))
                draft.scheduledDay?.let { o.put("scheduled_day", it) }
            }
            return o
        }

        /**
         * Why a "once" draft cannot be created, or null. The server stores a
         * once task with no usable future date as already completed (it never
         * runs), so the confirm step refuses it rather than failing silently.
         */
        fun onceProblem(draft: TaskDraft, zone: ZoneId, now: Instant): String? =
            onceProblem(draft.schedule, draft.scheduledDate, zone, now)

        /** [onceProblem] for any local "YYYY-MM-DDTHH:MM" date, so the editor checks what the draft step checks. */
        fun onceProblem(schedule: String, localDate: String?, zone: ZoneId, now: Instant): String? {
            if (schedule != "once") return null
            val iso = localDateTimeToUtcIso(localDate, zone)
                ?: return "This task has no readable date, so it would never run."
            return if (Instant.parse(iso) <= now) "That date has already passed, so the task would never run." else null
        }

        /**
         * The PUT /api/tasks/{id} body of an edit, built like the web form's
         * save (tasks.js _showForm): the same keys and the same conversions as
         * [createBody] (local time to UTC "HH:MM", a "once" date as a UTC ISO
         * instant, weekday and cron left as typed). A blank name is left out so
         * the server keeps the old one. What the editor does not show (model,
         * chain, persona, notifications, output) is left out too, and the
         * server only changes what a PUT names. An action task sends no
         * task_type or prompt: the web re-sends them only to change the
         * action, and sending them would re-seal the task's authority for
         * nothing.
         */
        fun updateBody(task: Task, f: TaskForm, zone: ZoneId, now: Instant): JSONObject {
            val o = JSONObject().put("trigger_type", task.triggerType)
            f.name.trim().takeIf { it.isNotEmpty() }?.let { o.put("name", it) }
            if (task.editsPrompt) o.put("task_type", task.taskType).put("prompt", f.prompt.trim())
            when (task.triggerType) {
                "schedule" -> {
                    o.put("schedule", f.schedule)
                    if (f.schedule == "cron") {
                        o.put("cron_expression", f.cron.trim())
                    } else {
                        o.put("scheduled_time", localTimeToUtc(f.time, zone, now))
                        when (f.schedule) {
                            "weekly" -> o.put("scheduled_day", f.day.trim().toIntOrNull()?.takeIf { it in 0..6 } ?: 0)
                            "monthly" -> f.day.trim().toIntOrNull()?.let { o.put("scheduled_day", it) }
                            "once" -> localDateTimeToUtcIso("${f.date}T${f.time}", zone)?.let { o.put("scheduled_date", it) }
                        }
                    }
                }
                "event" -> {
                    task.triggerEvent?.let { o.put("trigger_event", it) }
                    f.count.trim().toIntOrNull()?.let { o.put("trigger_count", it) }
                }
            }
            return o
        }

        /**
         * Why the edit cannot be saved, or null: the web form's own checks
         * (a prompt, a cron expression, an event count) plus the day of the
         * month the form's number box limits, and [onceProblem] for a "once"
         * date the person has set. A once task whose date was not touched is
         * let through, so a finished one-off can still be renamed.
         */
        fun editProblem(task: Task, f: TaskForm, zone: ZoneId, now: Instant): String? {
            if (task.editsPrompt && f.prompt.isBlank()) return "Prompt is required"
            when (task.triggerType) {
                "schedule" -> when (f.schedule) {
                    "cron" -> if (f.cron.isBlank()) return "Cron expression is required"
                    "monthly" -> if (f.day.trim().toIntOrNull() !in 1..31) return "Day of month must be 1 to 31"
                    "once" -> {
                        val iso = localDateTimeToUtcIso("${f.date}T${f.time}", zone)
                        val before = parseInstant(task.scheduledDate)?.truncatedTo(ChronoUnit.MINUTES)
                        val unchanged = task.schedule == "once" && iso != null && before == Instant.parse(iso).truncatedTo(ChronoUnit.MINUTES)
                        if (!unchanged) onceProblem("once", "${f.date}T${f.time}", zone, now)?.let { return it }
                    }
                }
                "event" -> if (f.count.trim().toIntOrNull() !in 1..1000) return "Every N occurrences must be 1 to 1000"
            }
            return null
        }

        /** "07:30" local to "HH:MM" UTC, using today's offset as the web does. */
        fun localTimeToUtc(hhmm: String, zone: ZoneId, now: Instant): String {
            val t = LocalTime.parse(pad(hhmm))
            val utc = now.atZone(zone).toLocalDate().atTime(t).atZone(zone).withZoneSameInstant(ZoneOffset.UTC)
            return utc.format(HHMM)
        }

        /** "HH:MM" UTC to "HH:MM" local, for display. */
        fun utcTimeToLocal(hhmm: String, zone: ZoneId, now: Instant): LocalTime {
            val t = LocalTime.parse(pad(hhmm))
            val local = now.atZone(ZoneOffset.UTC).toLocalDate().atTime(t).atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
            return local.toLocalTime()
        }

        /** "2026-10-12T07:30" local to "2026-10-12T06:30:00Z"; null when unreadable. */
        fun localDateTimeToUtcIso(value: String?, zone: ZoneId): String? {
            val v = value?.trim().orEmpty()
            val instant = runCatching { LocalDateTime.parse(v).atZone(zone).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(v) }.getOrNull()
                ?: return null
            return DateTimeFormatter.ISO_INSTANT.format(instant)
        }

        private fun pad(hhmm: String) = hhmm.trim().let { if (it.indexOf(':') == 1) "0$it" else it }

        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** 1st, 2nd, 3rd, 4th, 11th..13th. */
        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
            return "$n$suffix"
        }

        /** The web's _scheduleLabel: "Daily at 07:30", "Weekly on Monday at ...", "Cron: ...". */
        fun scheduleText(t: Task, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now(), locale: Locale = Locale.getDefault()): String {
            when (t.triggerType) {
                "event" -> {
                    val n = t.triggerCount ?: 1
                    val name = (t.triggerEvent ?: "event").replace('_', ' ')
                    return "Every $n $name${if (n > 1) "s" else ""}"
                }
                "webhook" -> return "Webhook"
            }
            if (t.schedule == "cron") return "Cron: ${t.cronExpression ?: "?"}"
            if (t.schedule == "once") {
                val at = parseInstant(t.scheduledDate) ?: return "Once"
                val local = at.atZone(zone)
                val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(local)
                val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(local)
                return "Once on $date at $time"
            }
            val time = runCatching {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
                    .format(utcTimeToLocal(t.scheduledTime ?: "00:00", zone, now))
            }.getOrDefault(t.scheduledTime ?: "00:00")
            return when (t.schedule) {
                "daily" -> "Daily at $time"
                "weekly" -> "Weekly on ${DAYS.getOrElse(t.scheduledDay ?: 0) { "Monday" }} at $time"
                "monthly" -> "Monthly on ${ordinal(t.scheduledDay ?: 1)} at $time"
                else -> t.schedule ?: "—"
            }
        }

        /** Like the web's _relativeTime: "in 3h", "5m ago", "just now". Empty when unreadable. */
        fun relativeTime(iso: String?, now: Instant): String {
            val at = parseInstant(iso) ?: return ""
            val diff = at.toEpochMilli() - now.toEpochMilli()
            val abs = Math.abs(diff)
            val past = diff < 0
            return when {
                abs < 60_000 -> if (past) "just now" else "in a moment"
                abs < 3_600_000 -> Math.round(abs / 60_000.0).let { if (past) "${it}m ago" else "in ${it}m" }
                abs < 86_400_000 -> Math.round(abs / 3_600_000.0).let { if (past) "${it}h ago" else "in ${it}h" }
                else -> Math.round(abs / 86_400_000.0).let { if (past) "${it}d ago" else "in ${it}d" }
            }
        }

        private fun parseInstant(value: String?): Instant? =
            com.thomaspeoples.wraithodysseus.ui.common.parseServerTime(value)

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
