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

/** The concrete Routes.EMAIL_READ path for [email]; navigation-compose
 * decodes the segments again before they reach the screen. */
fun emailReadRoute(email: EmailSummary): String =
    "email/read/${Uri.encode(accountArg(email.accountId))}/${Uri.encode(email.folder)}/${Uri.encode(email.uid)}"
