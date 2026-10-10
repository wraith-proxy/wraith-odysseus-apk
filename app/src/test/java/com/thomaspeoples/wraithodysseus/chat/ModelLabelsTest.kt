package com.thomaspeoples.wraithodysseus.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLabelsTest {

    private val local = ModelChoice("ep_local01", "Local llama", "org/qwen3-30b-a3b", "org/Qwen3 30B", "http://l", "local")
    private val cloud = ModelChoice("ep_cloud01", "Example Cloud", "gpt-5", "GPT-5", "https://c", "api")

    @Test
    fun localEndpointsAreCalledLocalAndOthersByTheirName() {
        assertEquals("Local", local.provider)
        assertEquals("Example Cloud", cloud.provider)
        assertEquals("Local · Qwen3 30B", local.label)
        assertEquals("Example Cloud · GPT-5", cloud.label)
    }

    @Test
    fun aDisplayNameLosesItsPathLikeTheWebs() {
        assertEquals("Example Cloud · llama", cloud.copy(display = "meta/llama").label)
        assertEquals("Example Cloud · x", cloud.copy(display = "x").label)
    }

    @Test
    fun groupsFollowEndpointsInServerOrderAndKeepSameNamedModelsApart() {
        val sameModelElsewhere = cloud.copy(model = "org/qwen3-30b-a3b", display = "Qwen3 30B")
        val groups = groupModels(listOf(local, cloud, sameModelElsewhere))
        assertEquals(listOf("ep_local01", "ep_cloud01"), groups.map { it.endpointId })
        assertEquals(listOf("Local", "Example Cloud"), groups.map { it.provider })
        assertEquals(listOf(listOf(local), listOf(cloud, sameModelElsewhere)), groups.map { it.models })
    }

    @Test
    fun groupsOfTheFixtureAreLocalThenCloud() {
        val json = checkNotNull(javaClass.classLoader?.getResource("fixtures/models_mixed.json")).readText()
        val groups = groupModels(ChatApi.parseModels(JSONObject(json)))
        assertEquals(listOf("Local", "Example Cloud"), groups.map { it.provider })
        // The same id on two endpoints is two entries.
        assertEquals(2, groups.flatMap { it.models }.count { it.model == "llama-3.1-8b" })
        assertTrue(groups.flatMap { it.models }.all { it.label.startsWith(it.provider + " · ") })
    }

    @Test
    fun theCurrentModelNeedsTheSameEndpointWhenKnown() {
        val twin = cloud.copy(model = local.model)
        assertTrue(isCurrentModel(local, local.model, "ep_local01"))
        assertTrue(!isCurrentModel(twin, local.model, "ep_local01"))
        assertTrue(isCurrentModel(twin, local.model, null))
    }

    @Test
    fun currentModelPrefersThePickThenTheListThenABareChoice() {
        val models = listOf(local, cloud)
        assertEquals(cloud, currentModel(models, cloud, local.model, "ep_local01"))
        assertEquals(local, currentModel(models, null, local.model, "ep_local01"))
        // Not in the list (offline endpoint): enough to create a chat on.
        val bare = currentModel(emptyList(), null, "gone", "ep_x")
        assertEquals("ep_x", bare?.endpointId)
        assertEquals("gone", bare?.model)
        assertNull(currentModel(models, null, null, "ep_local01"))
        assertNull(currentModel(emptyList(), null, "gone", null))
    }
}
