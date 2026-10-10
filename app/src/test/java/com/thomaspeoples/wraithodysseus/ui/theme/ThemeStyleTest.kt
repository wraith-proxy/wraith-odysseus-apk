package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeStyleTest {

    private fun stored(extra: String, name: String = "dark") =
        ThemeData.parseStored(JSONObject("""{"name":"$name","colors":{"bg":"#000000","fg":"#ffffff","panel":"#111111","border":"#222222"}$extra}"""))!!

    @Test
    fun fontNamesMapToFamilies() {
        assertSame(FiraCode, ThemeFont.parse("mono").family)
        assertSame(FontFamily.SansSerif, ThemeFont.parse("sans").family)
        assertSame(FontFamily.Serif, ThemeFont.parse("serif").family)
        assertSame(FontFamily.SansSerif, ThemeFont.parse("opendyslexic").family)
    }

    @Test
    fun missingRetiredAndUnknownFontsAreMono() {
        for (v in listOf(null, "", "gohu", "GohuFont", "Comic Sans", 7, "SANS")) assertEquals(ThemeFont.MONO, ThemeFont.parse(v))
    }

    @Test
    fun codeKeepsFiraOnlyForMono() {
        assertSame(FiraCode, ThemeFont.MONO.codeFamily)
        assertSame(FontFamily.Monospace, ThemeFont.SERIF.codeFamily)
    }

    @Test
    fun patternsTheAppCanDrawAndTheRest() {
        assertEquals(BgPattern.DOTS, BgPattern.parse("dots"))
        assertEquals(BgPattern.GRID, BgPattern.parse("synapse"))
        for (v in listOf("none", "rain", "petals", "starfield-depth", "ascii-fireflies", "bogus", "", null)) assertEquals(BgPattern.NONE, BgPattern.parse(v))
    }

    @Test
    fun storedFieldsAreRead() {
        val s = stored(""","font":"serif","bgPattern":"dots","bgEffectColor":"#F0A","bgEffectIntensity":0.4""").style
        assertEquals(ThemeFont.SERIF, s.font)
        assertEquals(BgPattern.DOTS, s.pattern)
        assertEquals("#ff00aa", s.effectColor)
        assertEquals(0.4f, s.intensity, 0.0001f)
    }

    @Test
    fun missingFieldsFallBackToThePresetsDefaults() {
        assertEquals(ThemeStyle.Default, stored("").style)
        val light = stored("", "light").style
        assertEquals(BgPattern.DOTS, light.pattern)
        assertEquals(1f, light.intensity, 0f)
        assertEquals(BgPattern.GRID, stored("", "cyberpunk").style.pattern)
        assertEquals("#62c4c7", stored("", "cyberpunk").style.effectColor)
        assertEquals(0.55f, stored("", "cyberpunk").style.intensity, 0.0001f)
    }

    @Test
    fun badValuesFallBack() {
        val s = stored(""","font":5,"bgPattern":7,"bgEffectColor":"red","bgEffectIntensity":"lots"""", "light").style
        assertEquals(ThemeFont.MONO, s.font)
        assertEquals(BgPattern.DOTS, s.pattern) // not a string: the preset's default
        assertEquals("", s.effectColor)
        assertEquals(1f, s.intensity, 0f)
    }

    @Test
    fun intensityIsClamped() {
        assertEquals(1f, ThemeStyle.parseIntensity(7), 0f)
        assertEquals(0f, ThemeStyle.parseIntensity(-2), 0f)
        assertEquals(0.5f, ThemeStyle.parseIntensity("0.5"), 0f)
        assertEquals(1f, ThemeStyle.parseIntensity(Double.NaN), 0f)
        assertEquals(1f, ThemeStyle.parseIntensity(JSONObject.NULL), 0f)
    }

    @Test
    fun retiredNameKeepsStoredFontAndTakesTheReplacementsPattern() {
        val t = ThemeData.parseStored(JSONObject("""{"name":"paper","font":"sans"}"""))!!
        assertEquals("monolith", t.name)
        assertEquals(ThemeFont.SANS, t.style.font)
        assertEquals(BgPattern.NONE, t.style.pattern) // perlin-flow is an animation
    }

    @Test
    fun presetOnlyThemeGetsItsStyle() {
        assertEquals(BgPattern.DOTS, ThemeData.parseStored(JSONObject("""{"name":"porcelain"}"""))!!.style.pattern)
    }

    @Test
    fun specUsesTheWebsAlphas() {
        val fg = Color.White
        val dots = PatternSpec.of(ThemeStyle(pattern = BgPattern.DOTS, intensity = 0.5f), fg)
        assertEquals(0.025f, dots.alpha, 0.0001f)
        assertEquals(fg, dots.color)
        val grid = PatternSpec.of(ThemeStyle(pattern = BgPattern.GRID, effectColor = "#ff0000"), fg)
        assertEquals(0.035f, grid.alpha, 0.0001f)
        assertEquals(Color(0xFFFF0000), grid.color)
        assertEquals(0f, PatternSpec.of(ThemeStyle.Default, fg).alpha, 0f)
    }

    @Test
    fun styleForNothingIsTheDefault() {
        assertSame(ThemeStyle.Default, styleFor(null))
    }
}
