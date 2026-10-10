package com.thomaspeoples.wraithodysseus.ui.theme

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.settings.PrefsApi
import java.io.IOException
import org.json.JSONException

/** The palette a stored theme gives; the dark look when there is none. */
fun paletteFor(stored: StoredTheme?): Palette = stored?.let { Palette.from(it.colors) } ?: Palette.Default

/** The font and background pattern a stored theme gives; the stock look when there is none. */
fun styleFor(stored: StoredTheme?): ThemeStyle = stored?.style ?: ThemeStyle.Default

/**
 * Reads the account's theme and makes it the app's. Unset becomes the dark
 * look; a failed read leaves what is showing, which at start is that same
 * dark look, so a flaky network never leaves the app half-themed.
 */
suspend fun syncTheme(prefs: PrefsApi) {
    try {
        val stored = ThemeData.parseStored(prefs.get("theme"))
        AppPalette.set(paletteFor(stored))
        AppStyle.set(styleFor(stored))
    } catch (_: IOException) {
    } catch (_: ApiException) {
    } catch (_: JSONException) {
    }
}
