package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.Http

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
)

/** One page of GET /api/email/list. [error] is set when the server reported
 * a failure inside an HTTP 200. */
data class EmailPage(val emails: List<EmailSummary>, val total: Int, val error: String?)

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

class EmailApi(private val http: Http) {
    suspend fun accounts(): List<EmailAccount> = TODO("U4")
    /** INBOX, filter=unread, limit 50. [accountId] null omits the parameter. */
    suspend fun unreadPage(accountId: String?, offset: Int): EmailPage = TODO("U4")
    suspend fun read(accountId: String?, folder: String, uid: String): EmailMessage = TODO("U4")
    suspend fun markUnread(accountId: String?, folder: String, uid: String): Unit = TODO("U4")
    suspend fun send(request: SendRequest): SendResult = TODO("U4")
}
