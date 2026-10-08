package com.thomaspeoples.wraithodysseus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostPolicy is the only thing standing between usesCleartextTraffic and a
 * session cookie sent in the clear over the internet, so the range edges are
 * pinned exactly.
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
    fun httpIsAcceptedForPrivateAddresses() {
        assertEquals("http://192.168.1.20:7000/", origin("http://192.168.1.20:7000"))
        assertEquals("http://10.0.2.2:5000/", origin("http://10.0.2.2:5000"))
        assertEquals("http://100.64.0.1/", origin("http://100.64.0.1"))
        assertEquals("http://[fd7a:115c:a1e0::1]/", origin("http://[fd7a:115c:a1e0::1]"))
    }

    @Test
    fun httpIsRejectedForPublicAddressesAndAnyHostname() {
        rejected("http://172.32.0.1")
        rejected("http://100.128.0.1")
        rejected("http://203.0.113.5")
        // Resolves privately only while Tailscale is up -- not good enough.
        rejected("http://foo.ts.net")
        rejected("http://localhost:8080")
    }

    @Test
    fun junkIsRejected() {
        rejected("")
        rejected("   ")
        rejected("ftp://192.168.1.2")
        rejected("https://")
    }

    @Test
    fun privateRangesHaveExactEdges() {
        assertTrue(HostPolicy.isPrivateHost("127.0.0.1"))
        assertTrue(HostPolicy.isPrivateHost("10.255.255.255"))
        assertTrue(HostPolicy.isPrivateHost("172.16.0.1"))
        assertTrue(HostPolicy.isPrivateHost("172.31.255.255"))
        assertFalse(HostPolicy.isPrivateHost("172.15.255.255"))
        assertFalse(HostPolicy.isPrivateHost("172.32.0.1"))
        assertTrue(HostPolicy.isPrivateHost("192.168.0.1"))
        assertFalse(HostPolicy.isPrivateHost("192.169.0.1"))
        assertTrue(HostPolicy.isPrivateHost("100.64.0.0"))
        assertTrue(HostPolicy.isPrivateHost("100.127.255.255"))
        assertFalse(HostPolicy.isPrivateHost("100.63.255.255"))
        assertFalse(HostPolicy.isPrivateHost("100.128.0.1"))
        assertTrue(HostPolicy.isPrivateHost("::1"))
        assertTrue(HostPolicy.isPrivateHost("fe80::1"))
        assertFalse(HostPolicy.isPrivateHost("2001:db8::1"))
        assertFalse(HostPolicy.isPrivateHost("fd.example.com"))
        assertFalse(HostPolicy.isPrivateHost("10.0.0"))
    }
}
