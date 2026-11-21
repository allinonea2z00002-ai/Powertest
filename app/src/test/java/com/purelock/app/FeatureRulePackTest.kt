package com.purelock.app

import com.purelock.app.data.content.FeatureRuleMatcher
import com.purelock.app.data.content.FeatureRulePack
import com.purelock.app.data.content.NodeSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FeatureRulePackTest {

    private val sampleJson = """
        {
          "version": 1,
          "rules": [
            {
              "id": "instagram_reels",
              "package": "com.instagram.android",
              "feature": "Reels",
              "viewIds": ["clips_viewer", "reels_tab"],
              "texts": ["Reels"],
              "descriptions": ["Reels tab"],
              "action": "BACK_THEN_HOME"
            },
            {
              "id": "youtube_shorts",
              "package": "com.google.android.youtube",
              "feature": "Shorts",
              "viewIds": ["shorts"],
              "texts": ["Shorts"],
              "descriptions": [],
              "action": "HOME"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parse_readsRules() {
        val rules = FeatureRulePack.parse(sampleJson)
        assertEquals(2, rules.size)
        assertEquals("instagram_reels", rules[0].id)
        assertEquals("com.instagram.android", rules[0].packageName)
        assertEquals(listOf("clips_viewer", "reels_tab"), rules[0].viewIds)
        assertEquals("HOME", rules[1].action)
    }

    @Test
    fun parse_rejectsMalformedJson() {
        try {
            FeatureRulePack.parse("{not json")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
        try {
            FeatureRulePack.parse("{\"version\": 1}")
            fail("Expected IllegalArgumentException for missing rules")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun match_byViewId() {
        val rules = FeatureRulePack.parse(sampleJson)
        val nodes = listOf(
            NodeSig(viewId = "com.instagram.android:id/clips_viewer", text = null, description = null)
        )
        assertEquals("instagram_reels", FeatureRuleMatcher.match(rules, "com.instagram.android", nodes)?.id)
    }

    @Test
    fun match_byWholeWordText() {
        val rules = FeatureRulePack.parse(sampleJson)
        val nodes = listOf(NodeSig(viewId = null, text = "Watch Reels now", description = null))
        assertNotNull(FeatureRuleMatcher.match(rules, "com.instagram.android", nodes))
        // "ReelsFoo" is not the whole word "Reels".
        val noMatch = listOf(NodeSig(viewId = null, text = "ReelsFoo", description = null))
        assertNull(FeatureRuleMatcher.match(rules, "com.instagram.android", noMatch))
    }

    @Test
    fun match_requiresSamePackage() {
        val rules = FeatureRulePack.parse(sampleJson)
        val nodes = listOf(NodeSig(viewId = null, text = "Reels", description = null))
        assertNull(FeatureRuleMatcher.match(rules, "com.example.other", nodes))
        assertNull(FeatureRuleMatcher.match(rules, "com.instagram.android", emptyList()))
    }

    @Test
    fun match_byDescriptionSubstring() {
        val rules = FeatureRulePack.parse(sampleJson)
        val nodes = listOf(NodeSig(viewId = null, text = null, description = "Open the Reels tab"))
        assertEquals("instagram_reels", FeatureRuleMatcher.match(rules, "com.instagram.android", nodes)?.id)
    }
}
