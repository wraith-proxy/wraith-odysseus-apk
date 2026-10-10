package com.thomaspeoples.wraithodysseus.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandTest {

    private val skills = listOf("summarize", "deploy-check")

    private fun parse(text: String) = SlashCommands.parse(text, skills)

    @Test
    fun eachPhoneCommandParses() {
        assertEquals(Slash.New, parse("/new"))
        assertEquals(Slash.Rename("Trip plan"), parse("/rename Trip plan"))
        assertEquals(Slash.Fork, parse("/fork"))
        assertEquals(Slash.Delete, parse("/delete"))
        assertEquals(Slash.Archive, parse("/archive"))
        assertEquals(Slash.Compact, parse("/compact"))
        assertEquals(Slash.Model(""), parse("/model"))
        assertEquals(Slash.Model("gpt-5"), parse("/model gpt-5"))
        assertEquals(Slash.Help, parse("/help"))
    }

    @Test
    fun caseSurroundingSpaceAndWebAliasesAreForgiven() {
        assertEquals(Slash.Compact, parse("  /COMPACT  "))
        assertEquals(Slash.Delete, parse("/rm"))
        assertEquals(Slash.Delete, parse("/del"))
        assertEquals(Slash.Rename("x"), parse("/mv x"))
        assertEquals(Slash.Help, parse("/?"))
        assertEquals(Slash.Fork, parse("/cp"))
    }

    @Test
    fun renameKeepsTheWholeNameAndBlankWhenGivenNone() {
        assertEquals(Slash.Rename("a  b c"), parse("/rename   a  b c"))
        assertEquals(Slash.Rename(""), parse("/rename"))
        assertEquals(Slash.Rename("line one\nline two"), parse("/rename line one\nline two"))
    }

    @Test
    fun skillsRunWithTheRestOfTheLineAsTheirRequest() {
        assertEquals(Slash.Skill("summarize", "the notes from today"), parse("/summarize the notes from today"))
        assertEquals(Slash.Skill("deploy-check", ""), parse("/Deploy-Check"))
    }

    @Test
    fun aBuiltInBeatsASkillOfTheSameName() {
        assertEquals(Slash.Compact, SlashCommands.parse("/compact now", listOf("compact")))
    }

    @Test
    fun anythingElseIsAnOrdinaryMessage() {
        assertNull(parse("hello"))
        assertNull(parse("/"))
        assertNull(parse("/etc/hosts what is it"))
        assertNull(parse("/unknown thing"))
        assertNull(parse("not /new"))
        assertNull(SlashCommands.parse("/summarize x", emptyList()))
    }

    @Test
    fun suggestionsAppearAsASlashIsTypedAndPrefixesComeFirst() {
        val skillList = listOf(SkillCommand("summarize", "Summarize text", "Skills / writing"), SkillCommand("renew-cert", "", "Skills"))
        assertEquals(SlashCommands.builtins.size + 2, SlashCommands.suggest("/", skillList, limit = 99).size)
        assertEquals(listOf("/rename", "/renew-cert"), SlashCommands.suggest("/ren", skillList).map { it.token })
        // Help text counts after prefixes: "/chat" is mentioned by five commands.
        assertEquals(listOf("/new", "/rename", "/fork", "/delete", "/archive"), SlashCommands.suggest("/chat", skillList).map { it.token })
        assertEquals("/rename", SlashCommands.suggest("/re", skillList).first().token)
        // The prefix first; /compact's "Summarize older messages" follows.
        assertEquals(listOf("/summarize", "/compact"), SlashCommands.suggest("/su", skillList).map { it.token })
        assertEquals("Run skill", SlashCommands.suggest("/renew", skillList).single().help)
    }

    @Test
    fun noSuggestionsOnceArgumentsAreBeingTypedOrWhenItIsNotACommand() {
        assertTrue(SlashCommands.suggest("/rename Tr", emptyList()).isEmpty())
        assertTrue(SlashCommands.suggest("hello", emptyList()).isEmpty())
        assertTrue(SlashCommands.suggest("", emptyList()).isEmpty())
        assertTrue(SlashCommands.suggest("/zzz", emptyList()).isEmpty())
    }

    @Test
    fun suggestionsAreCappedAndExactMatchIsFirst() {
        assertEquals(3, SlashCommands.suggest("/", emptyList(), limit = 3).size)
        assertEquals("/help", SlashCommands.suggest("/help", emptyList()).first().token)
    }

    @Test
    fun helpListsEveryCommandAndTheSkills() {
        val text = SlashCommands.helpText(listOf(SkillCommand("summarize", "", "")))
        SlashCommands.builtins.forEach { assertTrue(it.token, text.contains(it.token)) }
        assertTrue(text.contains("/summarize"))
    }
}
