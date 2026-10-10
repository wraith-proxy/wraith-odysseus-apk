package com.thomaspeoples.wraithodysseus.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import com.thomaspeoples.wraithodysseus.net.ApiException
import java.io.IOException
import org.json.JSONException

const val OFFLINE = "Could not reach the server. Is Tailscale connected?"

/** What to say when a call fails: the server's own words when it gave any,
 * otherwise what to check. Callers catch these three types and nothing
 * wider, so a cancelled coroutine is never reported as an error. */
fun userMessage(e: Exception): String = when (e) {
    is ApiException -> e.message
    is IOException -> OFFLINE
    is JSONException -> "Unexpected response from the server"
    else -> e.message ?: e.javaClass.simpleName
}

/** The app-wide snackbar, provided by AppNav: for a confirmation or an error
 * that needs no room of its own on the screen. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Opens [url] in the phone's browser. False when nothing can open it. */
fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
