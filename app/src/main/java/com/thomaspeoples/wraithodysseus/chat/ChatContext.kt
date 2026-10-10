package com.thomaspeoples.wraithodysseus.chat

import org.json.JSONObject

/**
 * GET /api/session/{sid}/context (history_routes.py:851): the whole chat's
 * estimated context use and the chat's own settings. [percent] is already
 * clamped to 0..100 by the server. Switches default to on when absent, as in
 * the web (`!== false`).
 */
data class ChatContext(
    val usedTokens: Int,
    val contextLength: Int,
    val percent: Double,
    /** Visible messages (the server leaves out hidden ones). */
    val messages: Int,
    val model: String,
    val canCompact: Boolean,
    val memoryExtraction: Boolean,
    val memoryInjection: Boolean,
    val skillInjection: Boolean,
    /** "on", "off" or "effort:<level>". */
    val thinkingMode: String,
    val thinkingSupported: Boolean,
    val temperatureOverride: Double?,
    val maxTokensOverride: Int?,
) {
    /** The level of "effort:<level>", or null. */
    val reasoningEffort get() = thinkingMode.takeIf { it.startsWith("effort:") }?.removePrefix("effort:")?.trim()?.ifEmpty { null }
}

/** The three per-chat switches of the Chat Context panel, by their route. */
enum class ChatSwitch(val path: String) {
    MemoryExtraction("memory-extraction"),
    MemoryInjection("memory-injection"),
    SkillInjection("skill-injection"),
}

/** The saved answer of POST generation-settings. */
data class GenerationSettings(val thinkingMode: String, val temperatureOverride: Double?, val maxTokensOverride: Int?)

/** One entry of GET /api/presets, a map keyed by id. */
data class Preset(val id: String, val name: String)

/** One skill of GET /api/skills/slash-catalog: run as "/<name> <request>". */
data class SkillCommand(val name: String, val help: String, val category: String)

private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeUnless { it.isNaN() }
private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

internal fun parseContext(o: JSONObject) = ChatContext(
    usedTokens = o.optInt("used_tokens", 0),
    contextLength = o.optInt("context_length", 0),
    percent = o.optDouble("context_percent", 0.0).coerceIn(0.0, 100.0),
    messages = o.optInt("messages", 0),
    model = o.str("model").orEmpty(),
    canCompact = o.optBoolean("can_compact", false),
    memoryExtraction = o.opt("memory_extraction_enabled") != false,
    memoryInjection = o.opt("memory_injection_enabled") != false,
    skillInjection = o.opt("skill_injection_enabled") != false,
    thinkingMode = o.str("thinking_mode") ?: "off",
    thinkingSupported = o.optBoolean("thinking_supported", false),
    temperatureOverride = o.doubleOrNull("temperature_override"),
    maxTokensOverride = o.intOrNull("max_tokens_override"),
)

internal fun parseGeneration(o: JSONObject) = GenerationSettings(
    thinkingMode = o.str("thinking_mode") ?: "off",
    temperatureOverride = o.doubleOrNull("temperature_override"),
    maxTokensOverride = o.intOrNull("max_tokens_override"),
)

/**
 * The presets the web lists: every entry with a name, except the "custom"
 * one while it is switched off (presets.js:539 treats an unconfigured custom
 * preset as disabled).
 */
internal fun parsePresets(root: JSONObject): List<Preset> = root.keys().asSequence().mapNotNull { id ->
    val o = root.optJSONObject(id) ?: return@mapNotNull null
    if (id == "custom" && !customOffered(o)) return@mapNotNull null
    Preset(id, o.str("name")?.ifBlank { null } ?: id)
}.toList()

/**
 * presets.js:525-545: the custom preset is off when `enabled` is false. With
 * no `enabled` key the web treats it as on if it holds anything (and off for
 * the untouched legacy default), so the phone does the same.
 */
internal fun customOffered(o: JSONObject): Boolean {
    if (o.has("enabled")) return o.optBoolean("enabled", false)
    val legacyPrompt = "You are a helpful, balanced assistant. Match your response style to the user's needs."
    if (o.str("name") == "Custom" && o.str("character_name").isNullOrEmpty() && o.str("system_prompt") == legacyPrompt) return false
    return !o.str("character_name").isNullOrEmpty() || !o.str("system_prompt").isNullOrEmpty() ||
        !o.str("inject_prefix").isNullOrEmpty() || !o.str("inject_suffix").isNullOrEmpty() ||
        !o.str("thinking_mode").isNullOrEmpty() || (o.doubleOrNull("temperature") ?: 1.0) != 1.0 ||
        (o.intOrNull("max_tokens") ?: 0) != 0
}

internal fun parseSkills(root: JSONObject): List<SkillCommand> {
    val arr = root.optJSONArray("skills") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
        SkillCommand(
            name = o.str("name")?.ifBlank { null } ?: return@mapNotNull null,
            help = o.str("help").orEmpty(),
            category = o.str("category").orEmpty(),
        )
    }
}
