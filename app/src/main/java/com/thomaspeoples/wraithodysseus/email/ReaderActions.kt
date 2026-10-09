package com.thomaspeoples.wraithodysseus.email

import java.time.OffsetDateTime

// The reader's pure decisions: who a Reply All goes to, what a Forward quotes,
// which message the arrows lead to. Mirrors static/js/emailInbox.js (the
// reply-all / forward branch of the reply builder) and replyRecipients.js.

/** The languages in the web's Translate submenu; "custom" is typed. */
val TRANSLATE_LANGUAGES = listOf("English", "Swedish", "Japanese", "Spanish", "French", "German")

/** Splits an address header on the commas between addresses, not the ones inside a quoted name or <...>. */
fun splitAddresses(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var angle = false
    for (c in raw) {
        when {
            c == '"' -> { quoted = !quoted; cur.append(c) }
            c == '<' && !quoted -> { angle = true; cur.append(c) }
            c == '>' && !quoted -> { angle = false; cur.append(c) }
            c == ',' && !quoted && !angle -> { out += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
    }
    out += cur.toString()
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

/** The bare, lower-cased address in "Name <a@b>" or "a@b" (the web's extractEmail). */
fun bareAddress(addr: String): String =
    Regex("<([^>]+)>").find(addr)?.groupValues?.get(1)?.trim()?.lowercase() ?: addr.trim().lowercase()

private fun mineSet(mine: Collection<String>) = mine.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

/**
 * Whether Reply All differs from Reply: the web shows it only when To and Cc
 * (plus the sender) hold more than one distinct address other than the user's
 * own. An empty [mine] excludes nothing.
 */
fun hasMultipleRecipients(m: EmailMessage, mine: Collection<String>): Boolean {
    val me = mineSet(mine)
    val others = (splitAddresses(m.to) + splitAddresses(m.cc)).map(::bareAddress).filter { it.isNotEmpty() && it !in me }.toMutableSet()
    val sender = m.fromAddress.trim().lowercase()
    if (sender.isNotEmpty() && sender !in me) others += sender
    return others.size > 1
}

/**
 * Reply All as the web builds it. From someone else: To is the sender, Cc is
 * everyone on the original To and Cc except the user's own addresses. From
 * the user (a Sent message): To is the people originally written to, Cc the
 * original Cc without anyone already in To. Threading headers are Reply's.
 */
fun replyAllDraft(original: EmailMessage, accountId: String?, mine: Collection<String>): SendRequest {
    val me = mineSet(mine)
    fun others(raw: String) = splitAddresses(raw).filter { bareAddress(it) !in me }
    val toOthers = others(original.to)
    val ccOthers = others(original.cc)
    val base = replyDraft(original, accountId)
    return if (original.fromAddress.trim().lowercase() in me) {
        val to = toOthers.joinToString(", ").ifEmpty { ccOthers.firstOrNull() ?: original.fromAddress }
        val cc = ccOthers.filter { c -> toOthers.none { bareAddress(it) == bareAddress(c) } }.joinToString(", ")
        base.copy(to = to, cc = cc)
    } else {
        // Nobody twice, and not the sender, who is already the To.
        base.copy(cc = addRecipients(listOf(original.fromAddress), toOthers + ccOthers).drop(1).joinToString(", "))
    }
}

private val FWD_PREFIX = Regex("^fwd?\\s*:", RegexOption.IGNORE_CASE)

/** "Fwd: " unless the subject already starts with Fwd: or Fw:. */
fun forwardSubject(subject: String): String {
    val base = subject.trim()
    return if (FWD_PREFIX.containsMatchIn(base)) base else "Fwd: $base"
}

/**
 * The forward of [original]: no recipient, no threading headers (the web
 * sends none for a forward) and nothing marked answered, with the message
 * pasted under a "Forwarded message" header. HTML-only mail is quoted from
 * its tags stripped, as the web does.
 */
fun forwardDraft(original: EmailMessage, accountId: String?): SendRequest {
    val text = quoteText(original)
    val from = if (original.fromName.isBlank()) original.fromAddress else "${original.fromName} <${original.fromAddress}>"
    val body = buildString {
        append("\n\n---------- Forwarded message ----------\n")
        append("From: $from\n")
        append("Date: ${niceDate(original.date)}\n")
        append("Subject: ${original.subject}\n")
        if (original.to.isNotBlank()) append("To: ${original.to}\n")
        append("\n").append(text)
    }
    return SendRequest(to = "", cc = "", subject = forwardSubject(original.subject), body = body, accountId = accountId)
}

/** The inbox row for a message that is open, which is what "Remind to reply" and the like take. */
fun EmailMessage.toSummary(accountId: String?): EmailSummary = EmailSummary(
    accountId = accountId,
    folder = folder,
    uid = uid,
    messageId = messageId,
    subject = subject,
    fromName = fromName,
    fromAddress = fromAddress,
    dateEpoch = runCatching { OffsetDateTime.parse(date).toEpochSecond().toDouble() }.getOrDefault(0.0),
    hasAttachments = attachments.isNotEmpty(),
    to = to,
)

/** The name the web saves a sender under: the display name, else the part before the @. */
fun contactName(m: EmailMessage): String = m.fromName.trim().ifEmpty { m.fromAddress.substringBefore('@') }

/** Where an open message sits in the list it was opened from. */
data class Neighbours(val previous: EmailSummary?, val next: EmailSummary?, val position: Int, val total: Int)

/** The rows either side of [key] in [list] (previous is the one above), or null when it is not there. */
fun neighbours(list: List<EmailSummary>, key: String): Neighbours? {
    val i = list.indexOfFirst { it.key == key }
    if (i < 0) return null
    return Neighbours(list.getOrNull(i - 1), list.getOrNull(i + 1), i + 1, list.size)
}
