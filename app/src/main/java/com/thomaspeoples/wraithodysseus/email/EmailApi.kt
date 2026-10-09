package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.sseFrames
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** An item of GET /api/email/accounts (wrapped in {"accounts": [...]}). */
data class EmailAccount(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val enabled: Boolean,
    val fromAddress: String,
)

/** A list item. The server does not say which account it came from, so the
 * client stamps [accountId] itself (null in the no-accounts fallback). */
data class EmailSummary(
    val accountId: String?,
    val folder: String,
    val uid: String,
    val messageId: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val dateEpoch: Double,
    val hasAttachments: Boolean,
    val isRead: Boolean = false,
    /** The web's "Done": the IMAP \\Answered flag. */
    val isAnswered: Boolean = false,
    /** The web's "Favorite": the IMAP \\Flagged flag. */
    val isFlagged: Boolean = false,
    /** The raw To header, which is who a Sent row is shown as. */
    val to: String = "",
) {
    /** Unique across accounts and folders; a uid alone is not. */
    val key: String get() = "$accountId/$folder/$uid"
}

/** One page of GET /api/email/list. [updatedAt] is sync.updated_at, which the
 * "Last: ..." line shows; null when the server did not say. */
data class EmailPage(val emails: List<EmailSummary>, val total: Int, val updatedAt: String? = null)

/** GET /api/email/unread-state. [maxUid] grows when new mail arrives. */
data class UnreadState(val count: Int, val maxUid: Long)

/** An item of GET /api/email/scheduled. */
data class ScheduledEmail(
    val id: String,
    val to: String,
    val subject: String,
    val sendAt: String,
    /** "pending" or "failed". */
    val status: String,
    val error: String,
)

/** POST /api/email/delete-bulk: which uids moved and which did not. */
data class BulkDelete(val deleted: List<String>, val failed: List<String>)

/** GET /api/email/read/{uid}. [body] is always plain text. */
data class EmailMessage(
    val uid: String,
    val folder: String,
    val messageId: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val to: String,
    val cc: String,
    val date: String,
    val references: String,
    val body: String,
    val markSeenFailed: Boolean,
)

/** POST /api/email/send, always with wait_for_delivery:true. The reply fields
 * are null for a new message. */
data class SendRequest(
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val accountId: String?,
    val inReplyTo: String? = null,
    val references: String? = null,
    val sourceUid: String? = null,
    val sourceFolder: String? = null,
)

sealed interface SendResult {
    data object Sent : SendResult
    /** A 504 or read timeout: it may still have gone out. Never retried. */
    data object Unconfirmed : SendResult
    data class Failed(val message: String) : SendResult
}

/** [sendTimeoutSeconds] is only ever changed by tests, to reach the
 * read-timeout outcome without waiting a full minute. */
class EmailApi(private val http: Http, private val sendTimeoutSeconds: Long = Http.SEND_TIMEOUT_S) {

    suspend fun accounts(): List<EmailAccount> {
        val list = checkBody(http.call(get(http.url("/api/email/accounts")))).optJSONArray("accounts")
            ?: return emptyList()
        return parseEach(list) { o ->
            EmailAccount(
                id = o.getString("id"),
                name = o.optString("name"),
                isDefault = o.optBoolean("is_default"),
                enabled = o.optBoolean("enabled", true),
                fromAddress = o.optString("from_address"),
            )
        }
    }

    /** INBOX, filter=unread, limit 50. [accountId] null omits the parameter. */
    suspend fun unreadPage(accountId: String?, offset: Int): EmailPage = listPage(accountId, INBOX, "unread", offset)

    /**
     * One page of GET /list, [PAGE_SIZE] rows. [cachedOnly] asks for the
     * server's local index only (fast, may be stale or empty); [refresh] is
     * pull-to-refresh, which makes the server drop its caches and re-ask the
     * mail provider. [filter] is one of [InboxFilters] (all, unread, tag:...).
     * The server does not say which account a row belongs to, so each row is
     * stamped with [accountId] here.
     */
    suspend fun listPage(
        accountId: String?,
        folder: String,
        filter: String,
        offset: Int,
        cachedOnly: Boolean = false,
        refresh: Boolean = false,
    ): EmailPage {
        val url = emailUrl(accountId, "list")
            .addQueryParameter("folder", folder)
            .addQueryParameter("filter", filter)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .apply {
                if (cachedOnly) addQueryParameter("cached_only", "1")
                if (refresh) addQueryParameter("refresh", "1")
            }
            .build()
        val json = checkBody(http.call(get(url)))
        val emails = parseEach(json.optJSONArray("emails") ?: JSONArray()) { summary(it, accountId, json.optString("folder", folder)) }
        val updated = json.optJSONObject("sync")?.optString("updated_at")?.takeIf { it.isNotBlank() }
        return EmailPage(emails, json.optInt("total", emails.size), updated)
    }

    /** GET /unread-state for [accountId]'s INBOX: cheap, reads the index. */
    suspend fun unreadState(accountId: String?): UnreadState {
        val url = emailUrl(accountId, "unread-state").addQueryParameter("folder", INBOX).build()
        val json = checkBody(http.call(get(url)))
        return UnreadState(json.optInt("unread_count"), json.optLong("max_uid"))
    }

    /** GET /folders: raw IMAP names. On a timeout the server answers 200 with
     * a made-up list plus an error, which is better than nothing here. */
    suspend fun folders(accountId: String?): List<String> {
        val body = http.call(get(emailUrl(accountId, "folders").build()))
        val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
        val list = json.optJSONArray("folders")
        if (list == null || list.length() == 0) checkBody(body)
        return (0 until (list?.length() ?: 0)).mapNotNull { list?.optString(it)?.takeIf(String::isNotBlank) }
    }

    /** GET /scheduled: pending and failed sends, soonest first. */
    suspend fun scheduled(): List<ScheduledEmail> {
        val list = checkBody(http.call(get(emailUrl(null, "scheduled").build()))).optJSONArray("scheduled") ?: return emptyList()
        return parseEach(list) { o ->
            ScheduledEmail(
                id = o.getString("id"),
                to = o.optString("to"),
                subject = o.optString("subject"),
                sendAt = o.optString("send_at"),
                status = o.optString("status"),
                error = o.optString("error").takeIf { it != "null" }.orEmpty(),
            )
        }
    }

    /** DELETE /scheduled/{id}. The server only deletes a still-pending send
     * and says success either way. */
    suspend fun cancelScheduled(id: String) {
        checkBody(http.call(Request.Builder().url(emailUrl(null, "scheduled", id).build()).delete().build()))
    }

    /**
     * GET /search. Under two characters nothing is sent, as the server would
     * answer empty anyway. [localOnly] asks for the index alone (fast); the
     * full search also asks the mail provider. [scope] "all" searches the
     * whole account (the server swaps INBOX for All Mail where there is one);
     * "folder" stays in [folder]. Rows carry the folder they were found in.
     */
    suspend fun search(accountId: String?, folder: String, query: String, scope: String, localOnly: Boolean): EmailPage {
        val q = query.trim()
        if (q.length < MIN_SEARCH) return EmailPage(emptyList(), 0)
        val url = emailUrl(accountId, "search")
            .addQueryParameter("q", q)
            .addQueryParameter("folder", folder)
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .addQueryParameter("scope", scope)
            .apply { if (localOnly) addQueryParameter("local_only", "1") }
            .build()
        val json = checkBody(http.call(get(url)))
        val inFolder = json.optString("folder", folder)
        val emails = parseEach(json.optJSONArray("emails") ?: JSONArray()) { summary(it, accountId, inFolder) }
        val updated = json.optJSONObject("sync")?.optString("updated_at")?.takeIf { it.isNotBlank() }
        return EmailPage(emails, json.optInt("total", emails.size), updated)
    }

    /** POST /flag/{uid}?on=: the Favorite toggle (\\Flagged). */
    suspend fun flag(accountId: String?, folder: String, uid: String, on: Boolean) =
        mutate("flag", accountId, folder, uid) { addQueryParameter("on", on.toString()) }

    suspend fun markRead(accountId: String?, folder: String, uid: String) = mutate("mark-read", accountId, folder, uid)

    suspend fun markAnswered(accountId: String?, folder: String, uid: String) = mutate("mark-answered", accountId, folder, uid)

    suspend fun clearAnswered(accountId: String?, folder: String, uid: String) = mutate("clear-answered", accountId, folder, uid)

    suspend fun archive(accountId: String?, folder: String, uid: String) = mutate("archive", accountId, folder, uid)

    /**
     * DELETE /delete/{uid}: moves to Trash. [messageId] lets the server
     * notice that the uid has been reused for another message since the list
     * was loaded. Like the web, only an explicit `success:true` counts.
     */
    suspend fun delete(accountId: String?, folder: String, uid: String, messageId: String) {
        val url = emailUrl(accountId, "delete", uid).addQueryParameter("folder", folder)
            .apply { if (messageId.isNotBlank()) addQueryParameter("message_id", messageId) }
            .build()
        val json = checkBody(http.call(Request.Builder().url(url).delete().build()))
        if (json.opt("success") != true) throw ApiException(200, "Request failed")
    }

    /**
     * POST /delete-bulk. Folder and account travel in the body here, not the
     * query. A uid the server could not move comes back in [BulkDelete.failed]
     * with the call itself still a success, so callers must look.
     */
    suspend fun deleteBulk(accountId: String?, folder: String, uids: List<String>): BulkDelete {
        val body = deleteBulkJson(accountId, folder, uids).toString().toRequestBody(JSON)
        val json = checkBody(http.call(Request.Builder().url(http.url("/api/email/delete-bulk")).post(body).build()))
        fun strings(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return BulkDelete(strings("deleted_uids"), strings("failed_uids"))
    }

    /** POST /api/notes: the body is built by [reminderNote]. */
    suspend fun createNote(note: JSONObject) {
        val body = note.toString().toRequestBody(JSON)
        checkBody(http.call(Request.Builder().url(http.url("/api/notes")).post(body).build()))
    }

    /** The server's root, e.g. https://odysseus.example, with no trailing slash. */
    fun origin(): String = http.url("/").toString().trimEnd('/')

    /** The server marks the message seen as a side effect (mark_seen defaults
     * to true), which is what drops it out of the unread list. */
    suspend fun read(accountId: String?, folder: String, uid: String): EmailMessage {
        val url = emailUrl(accountId, "read", uid).addQueryParameter("folder", folder).build()
        val o = checkBody(http.call(get(url)))
        return EmailMessage(
            uid = o.optString("uid", uid),
            folder = o.optString("folder", folder),
            messageId = o.optString("message_id"),
            subject = o.optString("subject"),
            fromName = o.optString("from_name"),
            fromAddress = o.optString("from_address"),
            to = o.optString("to"),
            cc = o.optString("cc"),
            date = o.optString("date"),
            references = o.optString("references"),
            body = o.optString("body"),
            markSeenFailed = o.optBoolean("mark_seen_failed"),
        )
    }

    suspend fun markUnread(accountId: String?, folder: String, uid: String) = mutate("mark-unread", accountId, folder, uid)

    /**
     * wait_for_delivery:true, so "Sent" means the SMTP server accepted it.
     * The server's 45s timeout still applies, so a 504 (or our own read
     * timeout) says nothing either way: the draft is kept and the caller is
     * told to check Sent rather than risk a duplicate by retrying. Only a
     * failure to connect at all is known not to have sent anything; any other
     * network error may have come after the body went out.
     */
    suspend fun send(request: SendRequest): SendResult {
        val body = sendJson(request).toString().toRequestBody(JSON)
        val response = try {
            // No retry: OkHttp would otherwise re-POST after a connection
            // failure, which can deliver twice and hides what really failed.
            http.call(Request.Builder().url(http.url("/api/email/send")).post(body).build(), sendTimeoutSeconds, retry = false)
        } catch (e: ApiException) {
            return if (e.status == 504) SendResult.Unconfirmed else SendResult.Failed(e.message)
        } catch (e: IOException) {
            return if (e is ConnectException || e is UnknownHostException) SendResult.Failed(describe(e))
            else SendResult.Unconfirmed
        }
        return try {
            checkBody(response)
            SendResult.Sent
        } catch (e: ApiException) {
            SendResult.Failed(e.message)
        }
    }

    /**
     * Drafts a reply with the server's AI, streamed. [onPreview] gets the full
     * visible text each time it changes (""  means start over), on the
     * caller's dispatcher. Returns once the result frame arrives; its text,
     * not the last preview, is the draft. Cancelling the caller closes the
     * response, which is what Stop does.
     *
     * Not retried (a second POST would start a second generation) and read
     * with 180s, not the stream default of 120s: the server sends no
     * heartbeat here and a slow local model can stay silent that long. The
     * server cuts non-streamed calls at 45s (app.py ~182-208), hence streaming.
     * HTTP and network failures throw ApiException / IOException; a stream
     * that ends with no result frame is a Failed outcome.
     */
    suspend fun aiReply(request: AiReplyRequest, onPreview: (String) -> Unit): AiReplyOutcome {
        val body = aiReplyJson(request).toString().toRequestBody(JSON)
        val post = Request.Builder().url(http.url("/api/email/ai-reply")).post(body).build()
        var progress = AiReplyProgress()
        http.open(post, AI_REPLY_TIMEOUT_S, retry = false).use { response ->
            response.sseFrames().collect { raw ->
                val frame = parseAiReplyFrame(raw.data) ?: return@collect
                val next = reduceAiReply(progress, frame)
                if (next.text != progress.text) onPreview(next.text)
                progress = next
            }
        }
        return progress.outcome ?: AiReplyOutcome.Failed("The AI reply ended before it finished")
    }

    /** A POST with an empty body that changes one message. Folder and account
     * go on every one: without them the server acts on INBOX of the default
     * account, which is the wrong message after a folder or account switch. */
    private suspend fun mutate(
        action: String,
        accountId: String?,
        folder: String,
        uid: String,
        extra: HttpUrl.Builder.() -> Unit = {},
    ) {
        val url = emailUrl(accountId, action, uid).addQueryParameter("folder", folder).apply(extra).build()
        checkBody(http.call(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()))
    }

    private fun summary(o: JSONObject, accountId: String?, folder: String) = EmailSummary(
        accountId = accountId,
        folder = o.optString("folder").ifBlank { folder },
        uid = o.getString("uid"),
        messageId = o.optString("message_id"),
        subject = o.optString("subject"),
        fromName = o.optString("from_name"),
        fromAddress = o.optString("from_address"),
        dateEpoch = o.optDouble("date_epoch", 0.0),
        hasAttachments = o.optBoolean("has_attachments"),
        isRead = o.optBoolean("is_read"),
        isAnswered = o.optBoolean("is_answered"),
        isFlagged = o.optBoolean("is_flagged"),
        to = o.optString("to"),
    )

    private fun get(url: HttpUrl) = Request.Builder().url(url).build()

    /** Segments are encoded one by one, so a uid can never add a path. */
    private fun emailUrl(accountId: String?, vararg segments: String): HttpUrl.Builder =
        http.url("/api/email").newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            if (accountId != null) addQueryParameter("account_id", accountId)
        }

    companion object {
        const val INBOX = "INBOX"
        const val AI_REPLY_TIMEOUT_S = 180L
        /** Same page size as the web UI, and small enough to stay well
         * inside the server's 45s request timeout. */
        const val PAGE_SIZE = 50
        const val MIN_SEARCH = 2
        const val SEARCH_LIMIT = 100
        private val JSON = "application/json".toMediaType()

        /**
         * Email routes report failure inside an HTTP 200, as `{"error": ...}`
         * or `{"success": false, "error": ...}`. This turns both into the same
         * ApiException a non-2xx answer would have thrown.
         */
        internal fun checkBody(body: String): JSONObject {
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            if (error != null) throw ApiException(200, error)
            if (json.opt("success") == false) throw ApiException(200, "Request failed")
            return json
        }

        /** Each entry through [item]. An entry that is not an object, or
         * lacks a required key, fails the call as checkBody would, rather
         * than escaping as a JSONException no caller catches. */
        private fun <T> parseEach(list: JSONArray, item: (JSONObject) -> T): List<T> = try {
            (0 until list.length()).map { item(list.getJSONObject(it)) }
        } catch (_: JSONException) {
            throw ApiException(200, "Unexpected response")
        }

        internal fun deleteBulkJson(accountId: String?, folder: String, uids: List<String>): JSONObject = JSONObject().apply {
            put("uids", JSONArray(uids))
            put("folder", folder)
            // The web sends an explicit null for the default account.
            put("account_id", accountId ?: JSONObject.NULL)
        }

        internal fun sendJson(r: SendRequest): JSONObject = JSONObject().apply {
            put("to", r.to)
            if (r.cc.isNotBlank()) put("cc", r.cc)
            put("subject", r.subject)
            put("body", r.body)
            // Left out entirely in the no-accounts fallback, so the server
            // picks its legacy settings or env config.
            r.accountId?.let { put("account_id", it) }
            r.inReplyTo?.let { put("in_reply_to", it) }
            r.references?.let { put("references", it) }
            r.sourceUid?.let { put("source_uid", it) }
            r.sourceFolder?.let { put("source_folder", it) }
            put("wait_for_delivery", true)
        }
    }
}
