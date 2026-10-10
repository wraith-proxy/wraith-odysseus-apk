package com.thomaspeoples.wraithodysseus.ui.icons

import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LucideTest {
    /** Building parses every path: one bad character in the generated data
     * fails here rather than on a phone, the first time the icon is drawn. */
    @Test
    fun everyIconBuilds() {
        assertEquals(89, Lucide.all.size)
        Lucide.all.forEach { (name, get) ->
            val icon = get()
            assertEquals(name, 24f, icon.viewportWidth)
            assertEquals(name, 24f, icon.viewportHeight)
            val paths = icon.root.filterIsInstance<VectorPath>()
            assertTrue(name, paths.isNotEmpty())
            assertTrue(name, paths.all { it.pathData.isNotEmpty() && it.strokeLineWidth == 2f && it.fill == null })
        }
    }
}
