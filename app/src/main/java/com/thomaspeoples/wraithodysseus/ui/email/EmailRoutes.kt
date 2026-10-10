package com.thomaspeoples.wraithodysseus.ui.email

import android.net.Uri
import com.thomaspeoples.wraithodysseus.email.EmailSummary

// Routes.EMAIL_READ is email/read/{acct}/{folder}/{uid}. In the no-accounts
// fallback there is no account id, and an empty path segment cannot fill
// {acct}, so this stands in for it.
private const val NO_ACCOUNT = "-"

/** The {acct} route segment for an account id (null in the fallback). */
fun accountArg(accountId: String?): String = accountId ?: NO_ACCOUNT

/** The account id back out of the {acct} route segment. */
fun accountFromArg(arg: String): String? = arg.takeIf { it != NO_ACCOUNT }

/**
 * What the inbox knows that a route cannot carry. The read endpoint returns
 * no flags, so the reader needs the row it was opened from to know whether
 * the message is favorited or done; and the arrows need the list the row came
 * from. Process-wide because the two screens are separate destinations, and
 * only ever a hint: a reader restored with nothing here shows the message and
 * treats its flags as unset.
 */
object ReadHandoff {
    @Volatile var opened: EmailSummary? = null

    /** The rows the inbox was showing, in order, for previous / next. The
     * inbox must set this before it opens a row for the arrows to appear. */
    @Volatile var siblings: List<EmailSummary> = emptyList()

    /** Keys of messages the reader moved out of their folder (archive, spam,
     * trash, delete); the inbox drops them when it comes back. */
    val removed: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
}

/** The concrete Routes.EMAIL_READ path for [email]; navigation-compose
 * decodes the segments again before they reach the screen. Also records
 * [email] in [ReadHandoff] for the reader that route opens. */
fun emailReadRoute(email: EmailSummary): String {
    ReadHandoff.opened = email
    return "email/read/${Uri.encode(accountArg(email.accountId))}/${Uri.encode(email.folder)}/${Uri.encode(email.uid)}"
}
