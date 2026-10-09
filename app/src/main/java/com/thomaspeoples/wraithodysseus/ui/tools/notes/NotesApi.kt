package com.thomaspeoples.wraithodysseus.ui.tools.notes

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One checklist row. [indent] is the web's nesting level (0..3). */
data class NoteItem(val id: String, val text: String, val done: Boolean, val indent: Int = 0)

/**
 * An item of GET /api/notes (note_routes.py _note_to_dict). [type] is what
 * the web stores in note_type: "note", or "todo" / "checklist" / "goal" (all
 * three have [items]), or "draw" (a picture in [imageUrl], which this app
 * shows but does not edit). [label] is a space-separated list of tags.
 * [dueDate] is naive local time, "yyyy-MM-dd" or "yyyy-MM-ddTHH:mm".
 */
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val items: List<NoteItem>,
    val type: String,
    val color: String,
    val label: String,
    val pinned: Boolean,
    val archived: Boolean,
    val dueDate: String?,
    val repeat: String,
    val source: String,
    val sortOrder: Int,
    val imageUrl: String?,
    val createdAt: String?,
    val updatedAt: String?,
) {
    val isChecklist: Boolean get() = type == "todo" || type == "checklist" || type == "goal"
}

/** What the editor saves. [items] is used for a checklist, [content] for a note. */
data class NoteDraft(
    val title: String,
    /** "note" or the note's own checklist type ("todo" for a new one). */
    val type: String,
    val content: String,
    val items: List<NoteItem>,
    val color: String,
    /** Space-separated tags, already normalised by [normaliseLabel]. */
    val label: String,
    /** Only sent when set: the web puts a new or edited note above the rest. */
    val sortOrder: Int? = null,
)

/**
 * The notes routes. Failures here are real HTTP errors (FastAPI `detail`), so
 * [Http] already turns them into [ApiException]; [checkBody] only guards the
 * odd 200 that carries an error anyway.
 */
class NotesApi(private val http: Http) {

    /** GET /api/notes, active notes pinned first, or with [archived] the
     * archived ones, most recently changed first. */
    suspend fun list(archived: Boolean): List<Note> {
        val url = notesUrl().apply { if (archived) addQueryParameter("archived", "true") }.build()
        val json = checkBody(http.call(Request.Builder().url(url).build()))
        return parseEach(json.optJSONArray("notes") ?: JSONArray(), ::parseNote)
    }

    /** POST /api/notes. Never retried: a lost answer must not make two notes. */
    suspend fun create(draft: NoteDraft): Note {
        val body = draftJson(draft, isNew = true, wasChecklist = false).toString().toRequestBody(JSON)
        return parse(checkBody(http.call(Request.Builder().url(notesUrl().build()).post(body).build(), retry = false)))
    }

    /** PUT /api/notes/{id}. The server only changes fields that are present. */
    suspend fun update(note: Note, draft: NoteDraft): Note {
        val body = draftJson(draft, isNew = false, wasChecklist = note.isChecklist).toString().toRequestBody(JSON)
        return parse(checkBody(http.call(Request.Builder().url(notesUrl(note.id).build()).put(body).build())))
    }

    /** DELETE /api/notes/{id}. */
    suspend fun delete(id: String) {
        checkBody(http.call(Request.Builder().url(notesUrl(id).build()).delete().build()))
    }

    /** POST /{id}/pin flips the pin and answers with the new state. */
    suspend fun togglePin(id: String): Boolean =
        checkBody(http.call(post(notesUrl(id, "pin").build()))).optBoolean("pinned")

    /** POST /{id}/archive flips archived and answers with the new state. */
    suspend fun toggleArchive(id: String): Boolean =
        checkBody(http.call(post(notesUrl(id, "archive").build()))).optBoolean("archived")

    /** POST /{id}/items/{index}/toggle: the server flips `done` and answers
     * with the whole checklist, which is what the screen should show. */
    suspend fun toggleItem(id: String, index: Int): List<NoteItem> {
        val json = checkBody(http.call(post(notesUrl(id, "items", index.toString(), "toggle").build())))
        return parseItems(json.optJSONArray("items"))
    }

    /** POST /api/notes/reorder: each id's position becomes its sort_order. */
    suspend fun reorder(ids: List<String>) {
        val body = JSONObject().put("ids", JSONArray(ids)).toString().toRequestBody(JSON)
        checkBody(http.call(Request.Builder().url(notesUrl("reorder").build()).post(body).build()))
    }

    private fun post(url: HttpUrl) = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()

    /** Segments are encoded one by one, so an id can never add a path. */
    private fun notesUrl(vararg segments: String): HttpUrl.Builder =
        http.url("/api/notes").newBuilder().apply { segments.forEach { addPathSegment(it) } }

    companion object {
        private val JSON = "application/json".toMediaType()

        internal fun checkBody(body: String): JSONObject {
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            if (error != null) throw ApiException(200, error)
            if (json.opt("success") == false || json.opt("ok") == false) throw ApiException(200, "Request failed")
            return json
        }

        private fun <T> parseEach(list: JSONArray, item: (JSONObject) -> T): List<T> = try {
            (0 until list.length()).map { item(list.getJSONObject(it)) }
        } catch (_: JSONException) {
            throw ApiException(200, "Unexpected response")
        }

        private fun parse(json: JSONObject): Note = try {
            parseNote(json)
        } catch (_: JSONException) {
            throw ApiException(200, "Unexpected response")
        }

        /** Strings the server may send as JSON null come back as "". */
        private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

        private fun JSONObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotBlank() }

        internal fun parseNote(o: JSONObject) = Note(
            id = o.getString("id"),
            title = o.str("title"),
            content = o.str("content"),
            items = parseItems(o.optJSONArray("items")),
            type = o.str("note_type").ifBlank { "note" },
            color = o.str("color"),
            label = o.str("label"),
            pinned = o.optBoolean("pinned"),
            archived = o.optBoolean("archived"),
            dueDate = o.strOrNull("due_date"),
            repeat = o.str("repeat").ifBlank { "none" },
            source = o.str("source"),
            sortOrder = o.optInt("sort_order"),
            imageUrl = o.strOrNull("image_url"),
            createdAt = o.strOrNull("created_at"),
            updatedAt = o.strOrNull("updated_at"),
        )

        /**
         * The checklist. Only `done` counts, as in the web UI and the
         * server's toggle; the email reminders of Phase 4 wrote `checked`,
         * which the web ignores too, so those rows show unchecked until
         * ticked. A row with no text is dropped, as the web's editor does.
         */
        internal fun parseItems(list: JSONArray?): List<NoteItem> {
            list ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                NoteItem(
                    id = o.str("id").ifBlank { "i$i" },
                    text = o.str("text"),
                    done = o.optBoolean("done"),
                    indent = o.optInt("indent").coerceIn(0, MAX_INDENT),
                )
            }
        }

        const val MAX_INDENT = 3

        /**
         * The save body. Due date and repeat are never sent, so the server
         * keeps them. An empty colour or label is sent as "" rather than
         * null, because the server ignores null and the web's null therefore
         * never cleared a label.
         */
        internal fun draftJson(d: NoteDraft, isNew: Boolean, wasChecklist: Boolean): JSONObject = JSONObject().apply {
            put("title", d.title)
            put("note_type", d.type)
            put("color", d.color)
            put("label", d.label)
            if (d.type == "note") {
                put("content", d.content)
                // Turning a checklist into a note must empty the old rows.
                if (wasChecklist) put("items", JSONArray())
            } else {
                put("items", JSONArray(d.items.map { JSONObject().put("id", it.id).put("text", it.text).put("done", it.done).put("indent", it.indent) }))
                if (d.type == "goal") put("content", d.content)
            }
            if (isNew) put("source", "user")
            d.sortOrder?.let { put("sort_order", it) }
        }
    }
}
