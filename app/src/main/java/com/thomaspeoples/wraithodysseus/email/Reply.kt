package com.thomaspeoples.wraithodysseus.email

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Reply headers built the way the web UI builds them (static/js/emailInbox.js),
// so a thread started in either client threads the same in a mail reader.

/** Prefixed with "Re: " unless it already starts with some form of "Re:". */
fun replySubject(subject: String): String {
    val base = subject.trim()
    return if (RE_PREFIX.containsMatchIn(base)) base else "Re: $base"
}

/** The original's references plus its own Message-ID, space separated. */
fun replyReferences(original: EmailMessage): String =
    "${original.references} ${original.messageId}".trim()

/** The ISO date the server sends, shown in [zone]; the raw text if it does
 * not parse. */
fun niceDate(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(NICE_DATE) }.getOrDefault(iso)

/** A blank line to type on, then the original quoted line by line. */
fun quotedBody(original: EmailMessage, zone: ZoneId = ZoneId.systemDefault()): String {
    val from = if (original.fromName.isBlank()) original.fromAddress
    else "${original.fromName} <${original.fromAddress}>"
    val quoted = quoteText(original).lines().joinToString("\n") { "> $it" }
    return "\n\n$REPLY_SEPARATOR\nOn ${niceDate(original.date, zone)}, $from wrote:\n$quoted"
}

/** The reply to [original] from [accountId], before the user edits it. With
 * no Message-ID there is nothing to thread on, so the headers are left out;
 * source_uid still lets the server mark the original answered. */
fun replyDraft(original: EmailMessage, accountId: String?): SendRequest {
    val threaded = original.messageId.isNotBlank()
    return SendRequest(
        to = original.fromAddress,
        cc = "",
        subject = replySubject(original.subject),
        body = quotedBody(original),
        accountId = accountId,
        inReplyTo = original.messageId.takeIf { threaded },
        references = replyReferences(original).takeIf { threaded },
        sourceUid = original.uid,
        sourceFolder = original.folder,
    )
}

private val RE_PREFIX = Regex("^re\\s*:", RegexOption.IGNORE_CASE)
private const val REPLY_SEPARATOR = "---------- Previous message ----------"
private val NICE_DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm", Locale.ENGLISH)
