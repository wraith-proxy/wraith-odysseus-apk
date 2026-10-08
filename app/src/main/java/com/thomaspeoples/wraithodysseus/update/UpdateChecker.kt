package com.thomaspeoples.wraithodysseus.update

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
 * Off entirely when BuildConfig.UPDATE_CHECK is false (an F-Droid build),
 * so such a build never contacts anything but the user's own server.
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
    suspend fun checkForUpdate(): UpdateInfo? {
        if (!BuildConfig.UPDATE_CHECK) return null
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
