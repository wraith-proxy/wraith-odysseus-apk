package com.thomaspeoples.wraithodysseus.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decides, once, at the moment a server URL is typed in, whether it is
 * allowed. The manifest permits cleartext so a LAN or Tailscale address can
 * be used over plain http; this is what keeps that from meaning "any host".
 * http is accepted only for a literal private address -- never a hostname,
 * because a name like foo.ts.net only resolves privately while Tailscale is
 * up, and the cookie would go out in the clear the one time it is not.
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
        // A bare host means https, the safe default -- never silently http.
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull()
            ?: return Verdict.Rejected("Not a valid http(s) address")
        if (!url.isHttps && !isPrivateHost(url.host)) {
            return Verdict.Rejected("Plain http is only allowed for a private IP address. Use https://")
        }
        return Verdict.Ok(url.newBuilder().encodedPath("/").query(null).fragment(null).build())
    }

    /**
     * Loopback, RFC 1918, Tailscale's CGNAT range (100.64.0.0/10), and the
     * IPv6 loopback, unique-local and link-local ranges. Literal addresses
     * only: OkHttp's HttpUrl has already canonicalised the host, so IPv6
     * arrives unbracketed and lower-case.
     */
    fun isPrivateHost(host: String): Boolean {
        val v4 = parseIpv4(host)
        if (v4 != null) {
            val (a, b) = v4
            return a == 127 ||
                a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }
        if (':' !in host) return false
        return host == "::1" ||
            host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
    }

    private fun parseIpv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { p -> p.toIntOrNull()?.takeIf { p.length in 1..3 && it in 0..255 } ?: return null }
    }
}
