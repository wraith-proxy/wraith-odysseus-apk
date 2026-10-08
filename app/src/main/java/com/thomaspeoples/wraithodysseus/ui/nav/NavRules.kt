package com.thomaspeoples.wraithodysseus.ui.nav

import com.thomaspeoples.wraithodysseus.net.SessionCookie

/** Login when there is no cookie or it has run out (the server's fixed 7
 * days), the Chats tab otherwise. A cookie the server has revoked early is
 * caught by the first 401 instead. */
internal fun startRoute(cookie: SessionCookie?, now: Long): String =
    if (cookie == null || cookie.expiresAt <= now) Routes.LOGIN else Routes.CHATS

/** Who a session belongs to: the same username on another server is someone
 * else. Null until both are known. */
internal fun identity(baseUrl: String?, username: String?): String? =
    if (baseUrl == null || username == null) null else "$username@$baseUrl"

/**
 * After an expired session is renewed, go back to the screen the user was
 * on -- but only if they logged back in as the same account. Anything else
 * (another user, another server, or a login with no expiry behind it) starts
 * clean at Chats, so no screen shows one account's data to another.
 */
internal fun returnAfterLogin(expiredFor: String?, loggedInAs: String?): Boolean =
    expiredFor != null && expiredFor == loggedInAs
