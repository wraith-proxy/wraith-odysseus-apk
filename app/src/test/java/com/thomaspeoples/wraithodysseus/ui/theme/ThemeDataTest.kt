package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDataTest {

    private fun fixture(name: String) =
        JSONObject(checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()).opt("value")

    @Test
    fun readsAStoredTheme() {
        val t = ThemeData.parseStored(fixture("theme_stored.json"))!!
        assertEquals("midnight", t.name)
        assertEquals(ThemeData.PRESETS.getValue("midnight"), t.colors)
    }

    @Test
    fun missingOrJunkValuesAreNull() {
        assertNull(ThemeData.parseStored(null))
        assertNull(ThemeData.parseStored("dark"))
        assertNull(ThemeData.parseStored(JSONObject("{}")))
        assertNull(ThemeData.parseStored(JSONObject("""{"name":"nonesuch","colors":{"bg":"zzz"}}""")))
    }

    @Test
    fun badColoursFallBackToTheNamedPreset() {
        val t = ThemeData.parseStored(JSONObject("""{"name":"forest","colors":{"bg":"nope","fg":"#fff","panel":"#000","border":"#111"}}"""))!!
        assertEquals(ThemeData.PRESETS.getValue("forest"), t.colors)
    }

    @Test
    fun badRedKeepsTheOtherColours() {
        val t = ThemeData.parseStored(JSONObject("""{"name":"x","colors":{"bg":"#000","fg":"#FFF","panel":"#111111","border":"#222222","red":"blue"}}"""))!!
        assertEquals("#000000", t.colors.bg)
        assertEquals("#ffffff", t.colors.fg)
        assertEquals(ThemeData.DEFAULT_RED, t.colors.red)
    }

    @Test
    fun renamedAndRetiredNamesMigrateAsTheWebDoes() {
        val old = ThemeData.parseStored(JSONObject("""{"name":"sakura","colors":${ThemeData.PRESETS.getValue("ume").toJson()}}"""))!!
        assertEquals("ume", old.name)
        val retired = ThemeData.parseStored(JSONObject("""{"name":"paper","colors":${ThemeData.PRESETS.getValue("dark").toJson()}}"""))!!
        assertEquals("monolith", retired.name)
        assertEquals(ThemeData.PRESETS.getValue("monolith"), retired.colors)
    }

    @Test
    fun advancedKeepsOnlyValidColours() {
        val c = ThemeData.parseColors(JSONObject("""{"bg":"#000000","fg":"#ffffff","panel":"#000000","border":"#111111","advanced":{"a":"#123456","b":"x","c":5}}"""))!!
        assertEquals(mapOf("a" to "#123456"), c.advanced)
    }

    @Test
    fun everyPresetIsWellFormedAndRoundTrips() {
        assertEquals(19, ThemeData.PRESETS.size)
        for ((name, c) in ThemeData.PRESETS) {
            assertEquals(name, c, ThemeData.parseColors(c.toJson()))
        }
    }

    @Test
    fun stockDarkIsTodaysLook() {
        assertSame(Palette.Default, Palette.from(ThemeData.PRESETS.getValue("dark")))
        assertEquals(Color(0xFF282C34), Palette.Default.background)
        assertFalse(Palette.Default.isLight)
    }

    @Test
    fun lightThemesGetALightPalette() {
        val p = Palette.from(ThemeData.PRESETS.getValue("light"))
        assertTrue(p.isLight)
        assertEquals(Color(0xFFF3F5F7), p.background)
        assertEquals(Color(0xFFB34D5C), p.primary)
        assertTrue(p.toColorScheme().background == p.background)
        assertEquals(Color.White, p.onPrimary)
    }

    @Test
    fun advancedOverridesReachTheBubblesAndInput() {
        val p = Palette.from(ThemeData.PRESETS.getValue("gpt"))
        assertEquals(Color(0xFF2F2F2F), p.userBubble)
        assertEquals(Color(0xFF2F2F2F), p.inputBg)
        assertEquals(Color(0xFF949494), p.sendBtn)
    }

    @Test
    fun defaultSchemeMatchesWhatTheAppShippedWith() {
        val s = Palette.Default.toColorScheme()
        assertEquals(Color(0xFFE06C75), s.primary)
        assertEquals(Color(0xFF111111), s.surface)
        assertEquals(Color(0xFF9CDEF2), s.onBackground)
        assertEquals(Color(0xFF355A66), s.outline)
        assertEquals(Color(0xFFFF4444), s.error)
    }

    @Test
    fun paletteForNothingIsTheDefault() {
        assertSame(Palette.Default, paletteFor(null))
    }

    @Test
    fun hexNormalising() {
        assertEquals("#aabbcc", ThemeData.normalizeHex("#ABC"))
        assertNull(ThemeData.normalizeHex("#12345"))
        assertNull(ThemeData.normalizeHex(null))
        assertEquals(Color(0xFF123456), ThemeData.toColor("#123456"))
    }

    @Test
    fun presetDefaultsMatchTheWebTables() {
        assertEquals("rain", ThemeData.defaultsFor("midnight").bgPattern)
        assertEquals(0.05, ThemeData.defaultsFor("organs").bgEffectSpeed, 0.0)
        assertEquals("none", ThemeData.defaultsFor("dark").bgPattern)
        assertEquals("starfield", ThemeData.displayName("ocean"))
    }
}
