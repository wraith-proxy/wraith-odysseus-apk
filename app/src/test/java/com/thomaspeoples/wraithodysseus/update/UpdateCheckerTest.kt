package com.thomaspeoples.wraithodysseus.update

import com.thomaspeoples.wraithodysseus.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check is the one piece of this app that does nothing visible
 * when it breaks -- it fails silently by design, so a regression here would
 * never surface as a crash, only as an app that quietly stops noticing
 * releases.
 */
class UpdateCheckerTest {

    @Test
    fun parsesAWellFormedReleaseTag() {
        assertEquals(Triple(1, 0, 2), UpdateChecker.parseVersion("android-1.0.2"))
        assertEquals(Triple(12, 7, 30), UpdateChecker.parseVersion("android-12.7.30"))
    }

    @Test
    fun rejectsTagsThatAreNotThisAppsReleases() {
        assertNull(UpdateChecker.parseVersion("v1.0.2"))
        assertNull(UpdateChecker.parseVersion("android-1.0.2-debug"))
        assertNull(UpdateChecker.parseVersion("android-1.0"))
        assertNull(UpdateChecker.parseVersion(""))
    }

    @Test
    fun ordersByMajorThenMinorThenPatch() {
        assertTrue(UpdateChecker.isNewer(Triple(1, 0, 3), Triple(1, 0, 2)))
        assertTrue(UpdateChecker.isNewer(Triple(1, 1, 0), Triple(1, 0, 9)))
        assertTrue(UpdateChecker.isNewer(Triple(2, 0, 0), Triple(1, 9, 9)))
    }

    @Test
    fun doesNotOfferAnUpdateToTheSameOrOlderVersion() {
        assertFalse(UpdateChecker.isNewer(Triple(1, 0, 2), Triple(1, 0, 2)))
        assertFalse(UpdateChecker.isNewer(Triple(1, 0, 1), Triple(1, 0, 2)))
        assertFalse(UpdateChecker.isNewer(Triple(0, 9, 9), Triple(1, 0, 0)))
    }

    @Test
    fun picksTheNewestNonDraftReleaseAboveCurrent() {
        val releases = """[
            {"tag_name":"android-0.3.0","draft":true,"html_url":"https://git.example.com/r/0.3.0"},
            {"tag_name":"android-0.2.0","draft":false,"html_url":"https://git.example.com/r/0.2.0"},
            {"tag_name":"v9.9.9","draft":false,"html_url":"https://git.example.com/r/v9"},
            {"tag_name":"android-0.1.1","draft":false,"html_url":"https://git.example.com/r/0.1.1"}
        ]"""
        assertEquals(
            UpdateChecker.UpdateInfo("0.2.0", "https://git.example.com/r/0.2.0"),
            UpdateChecker.newestRelease(releases, "0.1.0"),
        )
        assertNull(UpdateChecker.newestRelease(releases, "0.2.0"))
    }

    // Unit tests run the debug build, where UPDATE_CHECK is false, so the
    // sideload cases are pinned to the flag rather than to true.
    @Test
    fun anFdroidInstallNeverChecksAndOthersFollowTheBuildFlag() {
        listOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
            "dev.zapstore.app.fdroid",
            "com.aurora.adroid",
            "org.fdroid.fdroid.privileged",
        ).forEach { assertFalse(it, UpdateChecker.enabledFor(it, updateCheck = true)) }
        assertTrue(UpdateChecker.enabledFor("dev.imranr.obtainium", updateCheck = true))
        assertEquals(BuildConfig.UPDATE_CHECK, UpdateChecker.enabledFor(null))
        assertEquals(BuildConfig.UPDATE_CHECK, UpdateChecker.enabledFor("dev.imranr.obtainium"))
        assertEquals(BuildConfig.UPDATE_CHECK, UpdateChecker.enabledFor("com.google.android.packageinstaller"))
    }
}
