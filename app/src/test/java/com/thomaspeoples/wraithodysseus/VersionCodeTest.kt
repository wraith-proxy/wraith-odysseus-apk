package com.thomaspeoples.wraithodysseus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * versionCode is a literal in app/build.gradle.kts that bin/fdroid-bump.py
 * rewrites on each bump. This pins the built value to the documented scheme
 * (major*10000 + minor*100 + patch, at least 1), so a bump that skipped the
 * hook, or a hand edit, fails here instead of on F-Droid.
 */
class VersionCodeTest {

    private fun expected(name: String): Int {
        val (major, minor, patch) = name.split(".").map { it.toInt() }
        return maxOf(1, major * 10000 + minor * 100 + patch)
    }

    @Test
    fun buildConfigVersionCodeFollowsTheScheme() {
        assertEquals(expected(BuildConfig.VERSION_NAME), BuildConfig.VERSION_CODE)
    }

    @Test
    fun schemeExamples() {
        assertEquals(1, expected("0.0.0"))
        assertEquals(100, expected("0.1.0"))
        assertEquals(10003, expected("1.0.3"))
        assertEquals(19999, expected("1.99.99"))
    }
}
