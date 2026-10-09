package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.sseFrames
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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

/** GET /api/email/read/{uid}. [body] is always plain text; [bodyHtml] is the
 * server's raw HTML part (empty when there is none, as with scheduled mail)
 * and is NOT sanitised, so it must only ever reach the sandboxed reader. */
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
    val bodyHtml: String = "",
    val attachments: List<EmailAttachment> = emptyList(),
    /** Attachments of earlier messages in the thread ("From earlier in this thread"). */
    val relatedAttachments: List<EmailAttachment> = emptyList(),
    /** True when the server left the attachment list out of the read; it is then fetched separately. */
    val attachmentsDeferred: Boolean = false,
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
    /** Compose-upload tokens (see [EmailApi.stageAttachment]) to attach. */
    val attachments: List<String> = emptyList(),
)

/** The outcome of POST /translate. */
sealed interface TranslateResult {
    data class Done(val text: String, val language: String) : TranslateResult
    /** The mail was already in the target language. */
    data object SameLanguage : TranslateResult
    /** A 504: the gateway gave up, the model may still be working. */
    data object StillWorking : TranslateResult
}

/** POST /api/contacts/add: saved now, or the address was already there. */
enum class ContactResult { Saved, AlreadySaved }

/** POST /api/calendar/import. */
data class CalendarImport(val imported: Int, val skipped: Int, val calendar: String)

/** A fetched inline image: [mime] is the server's Content-Type. */
class InlineImage(val mime: String, val bytes: ByteArray)

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
            bodyHtml = if (o.isNull("body_html")) "" else o.optString("body_html"),
            attachments = parseAttachments(o.optJSONArray("attachments")),
            relatedAttachments = parseAttachments(o.optJSONArray("related_attachments")),
            attachmentsDeferred = o.optBoolean("attachments_deferred"),
        )
    }

    /** GET /attachments/{uid}: used when the read left the list out
     * ([EmailMessage.attachmentsDeferred]). Failure arrives as a 200 with `error`. */
    suspend fun attachments(accountId: String?, folder: String, uid: String): List<EmailAttachment> {
        val url = emailUrl(accountId, "attachments", uid).addQueryParameter("folder", folder).build()
        return parseAttachments(checkBody(http.call(get(url))).optJSONArray("attachments"))
    }

    /**
     * GET /attachment/{uid}/{index} streamed into [dest]. The server answers
     * a failure ("Email not found", "Mail operation failed") with HTTP 200 and
     * a JSON body, so the Content-Type is checked before anything is saved;
     * a real attachment always comes back as application/octet-stream.
     */
    suspend fun saveAttachment(accountId: String?, folder: String, uid: String, index: Int, dest: File) {
        val url = emailUrl(accountId, "attachment", uid, index.toString()).addQueryParameter("folder", folder).build()
        http.open(get(url), DOWNLOAD_TIMEOUT_S).use { response ->
            val type = response.header("Content-Type")
            if (isJsonType(type)) throw ApiException(200, attachmentError(response.body?.string().orEmpty()))
            withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                val input = response.body?.byteStream() ?: throw ApiException(200, "Unexpected response")
                try {
                    input.use { src -> dest.outputStream().use { src.copyTo(it) } }
                } catch (e: IOException) {
                    dest.delete()
                    throw e
                }
            }
        }
    }

    /** GET /inline-image/{uid}?cid=: null when the server has no such image
     * (a 404, or a JSON error in a 200). Shown in the reader's WebView, which
     * never gets the session cookie itself. */
    suspend fun inlineImage(accountId: String?, folder: String, uid: String, cid: String): InlineImage? {
        val response = try {
            http.open(get(inlineImageUrl(accountId, folder, uid, cid)), DOWNLOAD_TIMEOUT_S)
        } catch (_: ApiException) {
            return null
        }
        return response.use {
            val type = it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            if (!type.startsWith("image/")) null
            else withContext(Dispatchers.IO) { InlineImage(type, it.body?.bytes() ?: ByteArray(0)) }
        }
    }

    /** The inline-image URL the web builds: cid, folder and account_id as query. */
    fun inlineImageUrl(accountId: String?, folder: String, uid: String, cid: String): HttpUrl =
        emailUrl(accountId, "inline-image", uid).addQueryParameter("cid", cid).addQueryParameter("folder", folder).build()

    /** POST /move/{uid}?dest=: Move to Spam is dest=Junk, as the web sends it. */
    suspend fun move(accountId: String?, folder: String, uid: String, dest: String) =
        mutate("move", accountId, folder, uid) { addQueryParameter("dest", dest) }

    /** DELETE /delete-permanent/{uid}: expunges, with no Trash. Only an
     * explicit `success:true` counts, as for [delete]. */
    suspend fun deletePermanent(accountId: String?, folder: String, uid: String) {
        val url = emailUrl(accountId, "delete-permanent", uid).addQueryParameter("folder", folder).build()
        val json = checkBody(http.call(Request.Builder().url(url).delete().build()))
        if (json.opt("success") != true) throw ApiException(200, "Request failed")
    }

    /**
     * POST /translate. The model runs for up to 180s inside the request, so a
     * gateway in front of the server may cut in with a 504 while the work
     * carries on (and caches its result): that is [TranslateResult.StillWorking],
     * and asking again then returns the cached text. Not retried by OkHttp.
     */
    suspend fun translate(body: String, subject: String, from: String, language: String): TranslateResult {
        val payload = JSONObject().put("body", body).put("subject", subject).put("from", from)
            .put("target_language", language).put("auto", false)
        val request = Request.Builder().url(http.url("/api/email/translate")).post(payload.toString().toRequestBody(JSON)).build()
        val text = try {
            http.call(request, TRANSLATE_TIMEOUT_S, retry = false)
        } catch (e: ApiException) {
            if (e.status == 504) return TranslateResult.StillWorking
            throw e
        }
        val json = checkBody(text)
        if (json.opt("success") != true) throw ApiException(200, "Request failed")
        if (json.optBoolean("same_language")) return TranslateResult.SameLanguage
        val translated = cleanTranslation(json.optString("translation"))
        if (translated.isEmpty()) throw ApiException(200, "Empty response from model")
        return TranslateResult.Done(translated, json.optString("language", language))
    }

    /** POST /api/contacts/add {name, email}. The web falls back to the
     * address's local part for a missing name; the server does the same. */
    suspend fun addContact(name: String, email: String): ContactResult {
        val body = JSONObject().put("name", name).put("email", email).toString().toRequestBody(JSON)
        val json = checkBody(http.call(Request.Builder().url(http.url("/api/contacts/add")).post(body).build()))
        if (json.opt("success") != true) throw ApiException(200, "Failed to save contact")
        return if (json.optString("message") == "Already exists") ContactResult.AlreadySaved else ContactResult.Saved
    }

    /** POST /api/calendar/import: the .ics as multipart field `file`, as the web sends it. */
    suspend fun importCalendar(name: String, bytes: ByteArray): CalendarImport {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val json = checkBody(http.call(Request.Builder().url(http.url("/api/calendar/import")).post(form).build()))
        if (json.opt("ok") != true) throw ApiException(200, "Import failed")
        return CalendarImport(json.optInt("imported"), json.optInt("skipped"), json.optString("calendar"))
    }

    /** POST /compose-from-attachment/{uid}/{index}: copies a received attachment
     * into the compose uploads and returns the token /send takes, which is how
     * Forward sends the originals as real attachments. */
    suspend fun stageAttachment(accountId: String?, folder: String, uid: String, index: Int): String {
        val url = emailUrl(accountId, "compose-from-attachment", uid, index.toString()).addQueryParameter("folder", folder).build()
        val json = checkBody(http.call(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()))
        return json.optString("token").takeIf { it.isNotBlank() && json.opt("success") == true }
            ?: throw ApiException(200, "Could not attach the file")
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
        const val DOWNLOAD_TIMEOUT_S = 60L
        /** Just above the server's own 180s cut on the model call. */
        const val TRANSLATE_TIMEOUT_S = 190L
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
            if (r.attachments.isNotEmpty()) put("attachments", JSONArray(r.attachments))
            put("wait_for_delivery", true)
        }
    }
}
