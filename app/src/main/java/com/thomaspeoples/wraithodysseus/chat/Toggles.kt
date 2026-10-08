package com.thomaspeoples.wraithodysseus.chat

/**
 * The per-turn Agent / Web / Terminal / Research chips, with the same rules
 * as the web UI: agent mode is the master switch for tools, and terminal
 * (bash) only exists as an agent tool, so turning it on implies agent and
 * turning agent off clears it. Research is a self-contained mode, so it and
 * the agent tools exclude each other.
 */
data class Toggles(
    val agent: Boolean = false,
    val web: Boolean = false,
    val terminal: Boolean = false,
    val research: Boolean = false,
) {
    /** Research always runs as a chat-mode turn. */
    val agentMode get() = agent && !research

    fun toggleAgent() = if (agent) copy(agent = false, terminal = false) else copy(agent = true, research = false)
    fun toggleWeb() = if (web) copy(web = false) else copy(web = true, research = false)
    fun toggleTerminal() =
        if (terminal) copy(terminal = false) else copy(terminal = true, agent = true, research = false)
    fun toggleResearch() =
        if (research) copy(research = false) else Toggles(research = true)
}
