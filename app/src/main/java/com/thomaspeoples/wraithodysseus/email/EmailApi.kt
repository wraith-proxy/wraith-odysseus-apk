package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
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
) {
    /** Unique across accounts and folders; a uid alone is not. */
    val key: String get() = "$accountId/$folder/$uid"
}

/** One page of GET /api/email/list. */
data class EmailPage(val emails: List<EmailSummary>, val total: Int)

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
    suspend fun unreadPage(accountId: String?, offset: Int): EmailPage {
        val url = emailUrl(accountId, "list")
            .addQueryParameter("folder", INBOX)
            .addQueryParameter("filter", "unread")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val json = checkBody(http.call(get(url)))
        val items = json.optJSONArray("emails") ?: JSONArray()
        val folder = json.optString("folder", INBOX)
        val emails = parseEach(items) { o ->
            EmailSummary(
                accountId = accountId,
                folder = folder,
                uid = o.getString("uid"),
                messageId = o.optString("message_id"),
                subject = o.optString("subject"),
                fromName = o.optString("from_name"),
                fromAddress = o.optString("from_address"),
                dateEpoch = o.optDouble("date_epoch", 0.0),
                hasAttachments = o.optBoolean("has_attachments"),
            )
        }
        return EmailPage(emails, json.optInt("total", emails.size))
    }

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

    suspend fun markUnread(accountId: String?, folder: String, uid: String) {
        val url = emailUrl(accountId, "mark-unread", uid).addQueryParameter("folder", folder).build()
        checkBody(http.call(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()))
    }

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

    private fun get(url: HttpUrl) = Request.Builder().url(url).build()

    /** Segments are encoded one by one, so a uid can never add a path. */
    private fun emailUrl(accountId: String?, vararg segments: String): HttpUrl.Builder =
        http.url("/api/email").newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            if (accountId != null) addQueryParameter("account_id", accountId)
        }

    companion object {
        const val INBOX = "INBOX"
        /** Same page size as the web UI, and small enough to stay well
         * inside the server's 45s request timeout. */
        const val PAGE_SIZE = 50
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
