package com.thomaspeoples.wraithodysseus.chat

/**
 * The per-turn composer switches, with the same rules as the web UI: agent
 * mode is the master switch for tools, and terminal (bash) only exists as an
 * agent tool, so turning it on implies agent and turning agent off clears it.
 * Research is a self-contained mode, so it and the agent tools exclude each
 * other. Plan mode (a plan proposed, not run) is an agent turn too, so it
 * goes with agent and is dropped by research.
 *
 * [rag] is off by default: the web sends use_rag=false for every message
 * unless its RAG switch is on. [preset] is the prompt preset's id, if one is
 * picked (the web's selectedPreset).
 */
data class Toggles(
    val agent: Boolean = false,
    val web: Boolean = false,
    val terminal: Boolean = false,
    val research: Boolean = false,
    val plan: Boolean = false,
    val rag: Boolean = false,
    val preset: String? = null,
) {
    /** Research always runs as a chat-mode turn; plan mode forces agent (chat.js:2551). */
    val agentMode get() = (agent || plan) && !research

    /** What the server is told: research wins over plan (chat.js:2593). */
    val planMode get() = plan && !research

    fun toggleAgent() =
        if (agent) copy(agent = false, terminal = false, plan = false) else copy(agent = true, research = false)
    fun toggleWeb() = if (web) copy(web = false) else copy(web = true, research = false)
    fun toggleTerminal() =
        if (terminal) copy(terminal = false) else copy(terminal = true, agent = true, research = false)
    fun toggleResearch() =
        if (research) copy(research = false) else Toggles(research = true, rag = rag, preset = preset)
    fun togglePlan() =
        if (plan) copy(plan = false) else copy(plan = true, agent = true, research = false)
    fun toggleRag() = copy(rag = !rag)
    fun withPreset(id: String?) = copy(preset = id)

    /**
     * The Agent | Chat control. Each mode has its own default for Web: on in
     * agent (where it is a tool the model may use), off in chat (where it
     * means a search before every reply).
     */
    fun withMode(agentOn: Boolean) =
        if (agentOn) copy(agent = true, web = true, research = false)
        else copy(agent = false, web = false, terminal = false, plan = false, research = false)

    companion object {
        /** A chat opens in its own mode (ChatSession.mode "agent" or anything else). */
        fun forSessionMode(mode: String?) = Toggles().withMode(mode == "agent")
    }
}
