package com.thomaspeoples.wraithodysseus.ui.tools.theme

import com.thomaspeoples.wraithodysseus.ui.settings.PrefsApi
import com.thomaspeoples.wraithodysseus.ui.theme.PresetDefaults
import com.thomaspeoples.wraithodysseus.ui.theme.StoredTheme
import com.thomaspeoples.wraithodysseus.ui.theme.ThemeColors
import com.thomaspeoples.wraithodysseus.ui.theme.ThemeData
import org.json.JSONObject

/** A theme the user saved on the web (`custom-themes` preference). */
data class CustomTheme(val name: String, val raw: JSONObject, val colors: ThemeColors)

/**
 * The theme preference as the web UI reads and writes it. The web stores the
 * whole theme under one key, so a pick is always written in full.
 */
class ThemeApi(private val prefs: PrefsApi) {

    /** The saved theme, or null when none is stored or it is unreadable. */
    suspend fun load(): StoredTheme? = ThemeData.parseStored(prefs.get(KEY_THEME))

    suspend fun customThemes(): List<CustomTheme> = parseCustom(prefs.get(KEY_CUSTOM))

    suspend fun savePreset(name: String) = prefs.put(KEY_THEME, presetBody(name))

    suspend fun saveCustom(theme: CustomTheme) = prefs.put(KEY_THEME, customBody(theme))

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_CUSTOM = "custom-themes"

        /**
         * What theme.js `save()` builds for a swatch click: the name, the
         * colours, and only those extras that differ from the defaults.
         */
        private fun body(name: String, colors: JSONObject, font: String, density: String, d: PresetDefaults): JSONObject {
            val o = JSONObject().put("name", name).put("colors", colors)
            if (font != "mono") o.put("font", font)
            if (density != "comfortable") o.put("density", density)
            if (d.bgPattern != "none") o.put("bgPattern", d.bgPattern)
            if (d.bgEffectColor.isNotEmpty()) o.put("bgEffectColor", d.bgEffectColor)
            if (d.bgEffectIntensity != 1.0) o.put("bgEffectIntensity", d.bgEffectIntensity)
            if (d.bgEffectSize != 1.0) o.put("bgEffectSize", d.bgEffectSize)
            if (d.bgEffectSpeed != 1.0) o.put("bgEffectSpeed", d.bgEffectSpeed)
            if (d.frosted) o.put("frosted", true)
            return o
        }

        fun presetBody(name: String): JSONObject {
            val colors = checkNotNull(ThemeData.PRESETS[name]) { "Unknown preset $name" }
            return body(name, colors.toJson(), "mono", "comfortable", ThemeData.defaultsFor(name))
        }

        /** The web passes the saved entry itself as `colors`, extras and all. */
        fun customBody(theme: CustomTheme): JSONObject {
            val e = theme.raw
            val font = e.optString("font").let { if (it.isEmpty() || it == "gohu" || it == "GohuFont") "mono" else it }
            val d = PresetDefaults(
                bgPattern = e.optString("bgPattern").ifEmpty { "none" },
                bgEffectColor = e.optString("bgEffectColor"),
                bgEffectIntensity = if (e.has("bgEffectIntensity")) e.optDouble("bgEffectIntensity", 1.0) else 1.0,
                bgEffectSize = if (e.has("bgEffectSize")) e.optDouble("bgEffectSize", 1.0) else 1.0,
                bgEffectSpeed = if (e.has("bgEffectSpeed")) e.optDouble("bgEffectSpeed", 1.0) else 1.0,
                frosted = e.optBoolean("frosted", false),
            )
            return body(theme.name, JSONObject(e.toString()), font, e.optString("density").ifEmpty { "comfortable" }, d)
        }

        /** Entries without four good colours are skipped, not shown broken. */
        fun parseCustom(value: Any?): List<CustomTheme> {
            val o = value as? JSONObject ?: return emptyList()
            return o.keys().asSequence().mapNotNull { name ->
                val entry = o.optJSONObject(name) ?: return@mapNotNull null
                ThemeData.parseColors(entry)?.let { CustomTheme(name, entry, it) }
            }.toList()
        }
    }
}
