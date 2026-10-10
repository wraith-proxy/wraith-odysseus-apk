package com.thomaspeoples.wraithodysseus.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat_stream form per mode, as chat.js:2550-2610 builds it. */
class ComposerPayloadTest {

    private fun fields(
        t: Toggles,
        approval: ToolApproval? = null,
        canBash: Boolean = true,
        effort: String? = null,
    ) = ChatApi.formFields(ChatRequest("s1", "hi", t, null, emptyList(), approval, canBash, effort)).toMap()

    @Test
    fun chatModeSendsUseWebAndNeverAllowWebSearch() {
        val f = fields(Toggles(web = true))
        assertEquals("chat", f["mode"])
        assertEquals("true", f["use_web"])
        assertFalse("allow_web_search" in f)
        assertEquals("false", f["plan_mode"])
        // Chat with Web off sends neither.
        val off = fields(Toggles())
        assertFalse("use_web" in off || "allow_web_search" in off)
    }

    @Test
    fun agentModeSendsAllowWebSearchEitherWayAndNeverUseWeb() {
        val on = fields(Toggles().withMode(true))
        assertEquals("agent", on["mode"])
        assertEquals("true", on["allow_web_search"])
        assertFalse("use_web" in on)
        assertEquals("false", fields(Toggles(agent = true))["allow_web_search"])
    }

    @Test
    fun planModeMakesTheTurnAnAgentTurnAndSendsPlanMode() {
        val f = fields(Toggles(plan = true))
        assertEquals("agent", f["mode"])
        assertEquals("true", f["plan_mode"])
        assertEquals("false", f["allow_web_search"])
    }

    @Test
    fun planModeIsNeverSentWithAnApprovalOrResearch() {
        assertEquals("false", fields(Toggles(agent = true, plan = true), approval = ToolApproval("apr_1", "approve"))["plan_mode"])
        val research = fields(Toggles(plan = true, research = true))
        assertEquals("chat", research["mode"])
        assertEquals("false", research["plan_mode"])
        assertEquals("true", research["use_research"])
    }

    @Test
    fun presetIdIsSentOnlyWhenOnePicked() {
        assertEquals("code_analyze", fields(Toggles(preset = "code_analyze"))["preset_id"])
        assertFalse("preset_id" in fields(Toggles()))
        assertFalse("preset_id" in fields(Toggles(preset = " ")))
    }

    @Test
    fun ragIsOffUnlessSwitchedOnLikeTheWeb() {
        assertEquals("false", fields(Toggles())["use_rag"])
        assertFalse("use_rag" in fields(Toggles(rag = true)))
    }

    @Test
    fun allowBashIsTrueOnlyForAnAgentTurnOfAUserWhoMayUseIt() {
        assertEquals("true", fields(Toggles(agent = true, terminal = true))["allow_bash"])
        // No privilege: false whatever the switch says.
        assertEquals("false", fields(Toggles(agent = true, terminal = true), canBash = false)["allow_bash"])
        // A chat-mode turn has no bash tool.
        assertEquals("false", fields(Toggles(terminal = true))["allow_bash"])
        assertEquals("false", fields(Toggles(research = true, terminal = true, agent = true))["allow_bash"])
        // Always present: absence would leave an admin's agent its bash.
        assertTrue("allow_bash" in fields(Toggles()))
    }

    @Test
    fun reasoningEffortIsSentWhenTheChatHasOne() {
        assertEquals("high", fields(Toggles(), effort = "high")["reasoning_effort"])
        assertNull(fields(Toggles())["reasoning_effort"])
    }

    // -- the switches ------------------------------------------------------------

    @Test
    fun theModeControlSetsEachModesWebDefault() {
        assertEquals(Toggles(agent = true, web = true), Toggles().withMode(true))
        assertEquals(Toggles(), Toggles(agent = true, web = true, terminal = true, plan = true).withMode(false))
        // Switching keeps the rest of the composer: RAG and the preset.
        assertEquals(Toggles(agent = true, web = true, rag = true, preset = "reason"), Toggles(rag = true, preset = "reason").withMode(true))
    }

    @Test
    fun aChatStartsFromItsOwnMode() {
        assertEquals(Toggles(agent = true, web = true), Toggles.forSessionMode("agent"))
        assertEquals(Toggles(), Toggles.forSessionMode("chat"))
        assertEquals(Toggles(), Toggles.forSessionMode(null))
        assertEquals(Toggles(), Toggles.forSessionMode("research"))
    }

    @Test
    fun planGoesWithAgentAndResearchDropsIt() {
        val plan = Toggles().togglePlan()
        assertTrue(plan.plan && plan.agentMode && plan.planMode)
        // Agent off takes plan with it; research replaces it.
        assertFalse(plan.toggleAgent().plan)
        assertFalse(plan.toggleResearch().plan)
        assertFalse(Toggles(research = true).togglePlan().research)
    }

    @Test
    fun researchKeepsRagAndPreset() {
        assertEquals(Toggles(research = true, rag = true, preset = "p"), Toggles(agent = true, web = true, rag = true, preset = "p").toggleResearch())
    }
}
