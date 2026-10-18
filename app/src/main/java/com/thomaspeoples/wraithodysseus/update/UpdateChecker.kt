package com.thomaspeoples.wraithodysseus.update

import android.content.Context
import android.os.Build
import com.thomaspeoples.wraithodysseus.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Neither F-Droid nor Obtainium need this app's help to notice a new
 * release -- both already watch this repo's Gitea Releases on their own.
 * This exists for the third path: someone who sideloaded the APK directly
 * and has neither installed. It fails silently on any error (no network,
 * a malformed response, an unreachable host) -- a missed update check is
 * not worth interrupting the app over.
 *
 * Off when an F-Droid client installed the app, so an F-Droid install never
 * contacts anything but the user's own server. That is decided at runtime,
 * not by a build flag, because F-Droid ships the very APK attached to the
 * Gitea release (a reproducible build): both channels run one binary.
 * BuildConfig.UPDATE_CHECK = false (updateCheck=false) still compiles it out
 * of a self-built APK.
 */
object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val RELEASES_URL =
        "https://git.thomaspeoples.com/api/v1/repos/thomaspeoples/wraith-odysseus-apk/releases?limit=20"

    // Matches the android-X.Y.Z tags .cz.yaml cuts, and nothing else.
    private val TAG_RE = Regex("""^android-(\d+)\.(\d+)\.(\d+)$""")

    // ponytail: known F-Droid clients only; an unlisted one still gets the
    // check (it only asks Gitea for the release list). Add new clients here.
    private val FDROID_INSTALLERS = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.looker.droidify",
        "com.machiav3lli.fdroid",
        "dev.zapstore.app.fdroid",
        "com.aurora.adroid",
        "org.fdroid.fdroid.privileged",
    )

    // updateCheck is a parameter so the unit tests (a debug build, where the
    // flag is false) can still pin the installer list.
    internal fun enabledFor(
        installer: String?,
        updateCheck: Boolean = BuildConfig.UPDATE_CHECK,
    ): Boolean = updateCheck && installer !in FDROID_INSTALLERS

    /** Whether this install should check Gitea for updates at all. */
    fun enabled(context: Context): Boolean = enabledFor(
        try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
    )

    data class UpdateInfo(
        val latestVersionName: String,
        val releaseUrl: String,
    )

    internal fun parseVersion(name: String): Triple<Int, Int, Int>? =
        TAG_RE.matchEntire(name)?.destructured?.let { (maj, min, patch) ->
            Triple(maj.toInt(), min.toInt(), patch.toInt())
        }

    internal fun isNewer(candidate: Triple<Int, Int, Int>, current: Triple<Int, Int, Int>): Boolean {
        if (candidate.first != current.first) return candidate.first > current.first
        if (candidate.second != current.second) return candidate.second > current.second
        return candidate.third > current.third
    }

    /** The newest non-draft release above [currentVersionName], or null. */
    internal fun newestRelease(releasesJson: String, currentVersionName: String): UpdateInfo? {
        val current = parseVersion("android-$currentVersionName") ?: return null
        val releases = JSONArray(releasesJson)
        var best: Pair<Triple<Int, Int, Int>, UpdateInfo>? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", false)) continue
            val version = parseVersion(release.optString("tag_name", "")) ?: continue
            if (!isNewer(version, current)) continue
            if (best == null || isNewer(version, best.first)) {
                best = version to UpdateInfo(
                    latestVersionName = "${version.first}.${version.second}.${version.third}",
                    releaseUrl = release.optString("html_url", ""),
                )
            }
        }
        return best?.second
    }

    /** Null on any failure, and null when already current -- a dialog only
     * ever appears for a real, confirmed newer release. */
    suspend fun checkForUpdate(context: Context): UpdateInfo? {
        if (!enabled(context)) return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(RELEASES_URL).get().build()
                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string() ?: return@withContext null
                }
                newestRelease(body, BuildConfig.VERSION_NAME)
            } catch (_: Exception) {
                null
            }
        }
    }
}
