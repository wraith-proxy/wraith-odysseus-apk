package com.thomaspeoples.wraithodysseus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * https only: the manifest allows no cleartext, so HostPolicy has to turn
 * every http address away with a reason rather than let it fail later.
 */
class HostPolicyTest {

    private fun origin(input: String): String =
        (HostPolicy.check(input) as HostPolicy.Verdict.Ok).origin.toString()

    private fun rejected(input: String) =
        assertTrue(input, HostPolicy.check(input) is HostPolicy.Verdict.Rejected)

    @Test
    fun httpsIsAcceptedForAnyHost() {
        assertEquals("https://odysseus.example.ts.net:7011/", origin("https://odysseus.example.ts.net:7011"))
        assertEquals("https://203.0.113.5/", origin("https://203.0.113.5"))
        assertEquals("https://192.168.1.20/", origin("https://192.168.1.20"))
    }

    @Test
    fun aBareHostMeansHttpsNeverHttp() {
        assertEquals("https://odysseus.example.ts.net/", origin("  odysseus.example.ts.net  "))
    }

    @Test
    fun pathQueryAndFragmentAreDropped() {
        assertEquals("https://odysseus.example.com/", origin("https://odysseus.example.com/chat?x=1#top"))
    }

    @Test
    fun httpIsRejectedEverywhereWithTheReason() {
        for (url in listOf(
            "http://192.168.1.20:7000", "http://10.0.2.2:5000", "http://100.64.0.1",
            "http://[fd7a:115c:a1e0::1]", "http://127.0.0.1", "http://203.0.113.5",
            "http://foo.ts.net", "HTTP://localhost:8080",
        )) {
            assertEquals(url, HostPolicy.Verdict.Rejected(HostPolicy.NEEDS_HTTPS), HostPolicy.check(url))
        }
    }

    @Test
    fun junkIsRejected() {
        rejected("")
        rejected("   ")
        rejected("ftp://192.168.1.2")
        rejected("https://")
    }
}
