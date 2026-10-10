package com.thomaspeoples.wraithodysseus.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogglesTest {

    @Test
    fun planOffReturnsToChatMode() {
        val on = Toggles().togglePlan()
        assertTrue(on.agentMode)
        assertEquals(Toggles(), on.togglePlan())
        assertFalse(on.togglePlan().agentMode)
        assertEquals(Toggles(), on.toggleAgent())
    }

    @Test
    fun terminalImpliesAgent() {
        assertEquals(Toggles(agent = true, terminal = true), Toggles().toggleTerminal())
    }

    @Test
    fun agentOffClearsTerminal() {
        assertEquals(Toggles(), Toggles(agent = true, terminal = true).toggleAgent())
    }

    @Test
    fun researchClearsEveryAgentTool() {
        assertEquals(Toggles(research = true), Toggles(agent = true, web = true, terminal = true).toggleResearch())
    }

    @Test
    fun anyAgentToolDropsResearch() {
        val research = Toggles(research = true)
        assertEquals(Toggles(agent = true), research.toggleAgent())
        assertEquals(Toggles(web = true), research.toggleWeb())
        assertEquals(Toggles(agent = true, terminal = true), research.toggleTerminal())
    }

    @Test
    fun webAloneStaysInChatMode() {
        val web = Toggles().toggleWeb()
        assertTrue(web.web)
        assertFalse(web.agentMode)
        assertEquals(Toggles(), web.toggleWeb())
    }

    @Test
    fun formFieldsFollowTheMode() {
        fun fields(t: Toggles) = ChatApi.formFields(ChatRequest("s1", "hi", t, null, emptyList(), null)).toMap()

        val chatWeb = fields(Toggles(web = true))
        assertEquals("chat", chatWeb["mode"])
        assertEquals("true", chatWeb["use_web"])
        assertFalse("allow_web_search" in chatWeb)

        val agent = fields(Toggles(agent = true))
        assertEquals("agent", agent["mode"])
        assertEquals("false", agent["allow_web_search"])
        assertFalse("use_web" in agent)

        val research = fields(Toggles(research = true))
        assertEquals("chat", research["mode"])
        assertEquals("true", research["use_research"])

        // Bash is opted out explicitly, never by omission.
        assertEquals("false", fields(Toggles())["allow_bash"])
        assertEquals("true", fields(Toggles(agent = true, terminal = true))["allow_bash"])
        assertEquals("false", fields(Toggles())["plan_mode"])
    }
}
