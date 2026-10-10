package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * The five base colours of a web theme plus its optional `advanced` overrides
 * (static/js/theme.js `THEMES`). Every value is a checked `#rrggbb`.
 */
data class ThemeColors(
    val bg: String,
    val fg: String,
    val panel: String,
    val border: String,
    val red: String,
    val advanced: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bg", bg).put("fg", fg).put("panel", panel).put("border", border).put("red", red)
        .also { o -> if (advanced.isNotEmpty()) o.put("advanced", JSONObject(advanced)) }
}

/** The per-theme extras the web saves next to the colours. */
data class PresetDefaults(
    val bgPattern: String = "none",
    val bgEffectColor: String = "",
    val bgEffectIntensity: Double = 1.0,
    val bgEffectSize: Double = 1.0,
    val bgEffectSpeed: Double = 1.0,
    val frosted: Boolean = false,
)

/** What GET /api/prefs/theme holds: the theme's name, its colours and its font and background pattern. */
data class StoredTheme(val name: String, val colors: ThemeColors, val style: ThemeStyle = ThemeStyle.Default)

/**
 * The web's presets, copied from static/js/theme.js, and the reading of the
 * stored `theme` preference. Reading never throws: anything that is not a
 * usable theme becomes null, and the caller falls back to the dark look.
 */
object ThemeData {
    const val DEFAULT_NAME = "dark"
    const val DEFAULT_RED = "#e06c75"

    private fun t(bg: String, fg: String, panel: String, border: String, red: String, adv: Map<String, String> = emptyMap()) =
        ThemeColors(bg, fg, panel, border, red, adv)

    /** In the web's order. */
    val PRESETS: Map<String, ThemeColors> = linkedMapOf(
        "dark" to t("#282c34", "#9cdef2", "#111111", "#355a66", "#e06c75"),
        "light" to t("#f3f5f7", "#26313a", "#ffffff", "#cbd3da", "#b34d5c"),
        "midnight" to t("#0d1117", "#c9d1d9", "#161b22", "#30363d", "#f85149"),
        "cyberpunk" to t("#10121a", "#9bdde0", "#171a26", "#514b78", "#d45aa8"),
        "retrowave" to t("#1a1a2e", "#e94560", "#16213e", "#533483", "#e94560"),
        "forest" to t("#1b2a1b", "#a8d5a2", "#142414", "#3d6b3d", "#7cb871"),
        "ocean" to t("#0b1a2c", "#64d2ff", "#091422", "#1e5074", "#4facfe"),
        "ume" to t("#271d2b", "#f1d8e9", "#1d1721", "#66475f", "#d98bb7"),
        "terminal" to t("#0b1110", "#b8e6c1", "#080d0c", "#28553a", "#6bd38b"),
        "organs" to t("#0a0406", "#efe1c8", "#15080a", "#3a1519", "#c83240"),
        "gpt" to t(
            "#212121", "#ececec", "#171717", "#424242", "#949494",
            mapOf(
                "sendBtnBg" to "#949494", "sendBtnHover" to "#7f7f7f", "userBubbleBg" to "#2f2f2f",
                "aiBubbleBg" to "#171717", "inputBg" to "#2f2f2f", "brandColor" to "#ffffff", "brandMixTo" to "#ffffff",
            ),
        ),
        "claude" to t("#1f1e1b", "#f5f1e8", "#2b2926", "#514b43", "#d97757"),
        "cute" to t("#fff4f7", "#63394d", "#fffafd", "#edc7d5", "#d65f88"),
        "eclipse" to t("#17191c", "#e8e4dc", "#0f1113", "#3c4248", "#46c2b3"),
        "porcelain" to t("#edf0f2", "#252a30", "#ffffff", "#c7cdd2", "#3478c9"),
        "arcade" to t("#15131d", "#f4e85c", "#0b1720", "#305f68", "#ff4f91"),
        "blueprint" to t("#10263b", "#e8f1f5", "#091a29", "#48758d", "#e6b84a"),
        "monolith" to t("#202020", "#d8d8d2", "#121212", "#50504b", "#b6e05c"),
        "yoyo" to t("#211f23", "#dfdbd7", "#141415", "#3e5146", "#e09d6c"),
    )

    /** The web shows "ocean" as "starfield". */
    fun displayName(name: String): String = if (name == "ocean") "starfield" else name

    private val PATTERNS = mapOf(
        "light" to "dots", "midnight" to "rain", "cyberpunk" to "synapse", "retrowave" to "embers",
        "forest" to "petals", "ocean" to "starfield-depth", "terminal" to "ascii-fireflies", "organs" to "rain",
        "ume" to "petals", "cute" to "sparkles", "eclipse" to "starfield-depth", "porcelain" to "dots",
        "arcade" to "synapse", "blueprint" to "dots", "monolith" to "perlin-flow", "yoyo" to "ascii-fireflies",
    )
    private val EFFECT_COLORS = mapOf(
        "midnight" to "#ffffff", "cyberpunk" to "#62c4c7", "organs" to "#451616", "cute" to "#ff8cb8",
        "ume" to "#f5a0c0", "eclipse" to "#46c2b3", "arcade" to "#ff4f91", "blueprint" to "#e6b84a",
        "monolith" to "#b6e05c", "yoyo" to "#b8e6c1",
    )
    private val INTENSITY = mapOf("midnight" to 0.5, "cyberpunk" to 0.55, "terminal" to 0.8, "yoyo" to 0.8, "organs" to 0.75)

    /** What the web's swatch click saves besides the colours (its THEME_DEFAULT_* tables). */
    fun defaultsFor(name: String) = PresetDefaults(
        bgPattern = PATTERNS[name] ?: "none",
        bgEffectColor = EFFECT_COLORS[name] ?: "",
        bgEffectIntensity = INTENSITY[name] ?: 1.0,
        bgEffectSize = if (name == "organs") 0.75 else 1.0,
        bgEffectSpeed = if (name == "organs") 0.05 else 1.0,
    )

    /** Names the web renamed or retired, and what they became (theme.js getSaved). */
    private val RENAMED = mapOf("chatgpt" to "gpt", "sakura" to "ume")
    private val RETIRED = mapOf("paper" to "monolith", "copper" to "arcade", "lavender" to "porcelain")

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")
    private val HEX3 = Regex("^#[0-9a-fA-F]{3}$")

    /** `#rgb` or `#rrggbb` as lowercase `#rrggbb`; null for anything else. */
    fun normalizeHex(value: Any?): String? {
        val s = (value as? String)?.trim() ?: return null
        return when {
            HEX.matches(s) -> s.lowercase()
            HEX3.matches(s) -> "#" + s.substring(1).map { "$it$it" }.joinToString("").lowercase()
            else -> null
        }
    }

    fun toColor(hex: String): Color = Color(0xFF000000L or hex.substring(1).toLong(16))

    /** A colours object, or null when one of the four required colours is missing or malformed. */
    fun parseColors(o: JSONObject?): ThemeColors? {
        o ?: return null
        val bg = normalizeHex(o.opt("bg")) ?: return null
        val fg = normalizeHex(o.opt("fg")) ?: return null
        val panel = normalizeHex(o.opt("panel")) ?: return null
        val border = normalizeHex(o.opt("border")) ?: return null
        val red = normalizeHex(o.opt("red")) ?: DEFAULT_RED
        val adv = linkedMapOf<String, String>()
        o.optJSONObject("advanced")?.let { a ->
            a.keys().forEach { k -> normalizeHex(a.opt(k))?.let { adv[k] = it } }
        }
        return ThemeColors(bg, fg, panel, border, red, adv)
    }

    /**
     * The stored `theme` value. Stored colours win, as in the web; a retired
     * name takes its replacement's colours; colours that do not parse fall
     * back to the named preset.
     */
    fun parseStored(value: Any?): StoredTheme? {
        val o = value as? JSONObject ?: return null
        var name = o.optString("name").trim()
        name = RENAMED[name] ?: name
        // The font and pattern fields survive a retired name; only the name and colours are replaced.
        RETIRED[name]?.let { return StoredTheme(it, PRESETS.getValue(it), ThemeStyle.from(o, it)) }
        parseColors(o.optJSONObject("colors"))?.let {
            val effective = name.ifEmpty { DEFAULT_NAME }
            return StoredTheme(effective, it, ThemeStyle.from(o, effective))
        }
        return PRESETS[name]?.let { StoredTheme(name, it, ThemeStyle.from(o, name)) }
    }
}
