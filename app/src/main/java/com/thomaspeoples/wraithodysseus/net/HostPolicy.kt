package com.thomaspeoples.wraithodysseus.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decides, once, at the moment a server URL is typed in, whether it is
 * allowed: https only, to any host. The manifest forbids cleartext outright
 * (Android's default since API 28), so a plain http address could never
 * connect anyway; refusing it here says why instead of failing later.
 */
object HostPolicy {

    sealed interface Verdict {
        /** The server's origin (scheme, host, port), path and query dropped. */
        data class Ok(val origin: HttpUrl) : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    fun check(input: String): Verdict {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Verdict.Rejected("Enter the server address")
        // A bare host means https.
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull()
            ?: return Verdict.Rejected("Not a valid https address")
        if (!url.isHttps) return Verdict.Rejected(NEEDS_HTTPS)
        return Verdict.Ok(url.newBuilder().encodedPath("/").query(null).fragment(null).build())
    }

    const val NEEDS_HTTPS = "Plain http is not supported. Use the server's https:// address"
}
