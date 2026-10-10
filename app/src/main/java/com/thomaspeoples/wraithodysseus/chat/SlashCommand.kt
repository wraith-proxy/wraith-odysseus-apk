package com.thomaspeoples.wraithodysseus.chat

/**
 * What a typed "/..." line means. These are the web's chat commands that
 * make sense on a phone (slashCommands.js: /chats new, rename, fork, delete,
 * archive, /compact, /model, /help), kept under their short names, plus the
 * server's skills. They are handled on the phone and never sent to the model.
 * Anything else starting with "/" (a path, say) is an ordinary message: the
 * web passes unknown commands through to the AI too (slashCommands.js:6411).
 */
sealed interface Slash {
    data object New : Slash
    /** [name] is blank when the command was typed with none. */
    data class Rename(val name: String) : Slash
    data object Fork : Slash
    data object Delete : Slash
    data object Archive : Slash
    data object Compact : Slash
    /** [query] is blank for "show the current model". */
    data class Model(val query: String) : Slash
    data object Help : Slash
    /** Run skill [name] on [request]: invoke it, then send what it returns. */
    data class Skill(val name: String, val request: String) : Slash
}

/** One row of the list that appears while a "/" is typed. */
data class SlashSuggestion(val token: String, val help: String, val category: String)

object SlashCommands {
    /** The phone's own commands, in the order they are offered. */
    val builtins = listOf(
        SlashSuggestion("/new", "New chat on this model", "Chats"),
        SlashSuggestion("/rename", "Rename this chat: /rename New name", "Chats"),
        SlashSuggestion("/fork", "Copy this chat into a new one", "Chats"),
        SlashSuggestion("/delete", "Delete this chat", "Chats"),
        SlashSuggestion("/archive", "Archive this chat", "Chats"),
        SlashSuggestion("/compact", "Summarize older messages", "Utility"),
        SlashSuggestion("/model", "Show or switch the model: /model name", "Settings"),
        SlashSuggestion("/help", "List the commands", "Utility"),
    )

    /** Aliases the web accepts for the same commands. */
    private val aliases = mapOf(
        "create" to "new", "mv" to "rename", "cp" to "fork", "del" to "delete", "rm" to "delete",
        "tar" to "archive", "?" to "help", "commands" to "help",
    )

    /**
     * Parses [text] (as typed, trimmed here). A built-in command wins over a
     * skill of the same name, as in the web. Null means "not a command".
     */
    fun parse(text: String, skills: Collection<String>): Slash? {
        val t = text.trim()
        if (!t.startsWith("/") || t.length < 2) return null
        val word = t.drop(1).substringBefore(' ').substringBefore('\n').lowercase()
        val args = t.drop(1).drop(word.length).trim()
        return when (aliases[word] ?: word) {
            "new" -> Slash.New
            "rename" -> Slash.Rename(args)
            "fork" -> Slash.Fork
            "delete" -> Slash.Delete
            "archive" -> Slash.Archive
            "compact" -> Slash.Compact
            "model" -> Slash.Model(args)
            "help" -> Slash.Help
            else -> skills.firstOrNull { it.equals(word, ignoreCase = true) }?.let { Slash.Skill(it, args) }
        }
    }

    /**
     * The list shown while the first word of the draft starts with "/":
     * token prefix first, then anything containing it, like the web
     * (slashAutocomplete.js _scoreMatch). Empty once the user is typing
     * arguments, i.e. after a space.
     */
    fun suggest(draft: String, skills: List<SkillCommand>, limit: Int = 8): List<SlashSuggestion> {
        if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
        val q = draft.lowercase()
        val all = builtins + skills.map { SlashSuggestion("/${it.name}", it.help.ifBlank { "Run skill" }, "Skills") }
        return all.distinctBy { it.token.lowercase() }
            .mapNotNull { s ->
                val t = s.token.lowercase()
                val score = when {
                    t == q -> 3
                    t.startsWith(q) -> 2
                    q.length > 1 && s.help.lowercase().contains(q.drop(1)) -> 1
                    else -> return@mapNotNull null
                }
                score to s
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .take(limit)
    }

    /** The /help text. */
    fun helpText(skills: List<SkillCommand>): String = buildString {
        builtins.forEach { appendLine("${it.token}  ${it.help}") }
        if (skills.isNotEmpty()) {
            appendLine()
            append("Skills: ").append(skills.joinToString(", ") { "/${it.name}" })
        }
    }.trimEnd()
}
