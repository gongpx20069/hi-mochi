package com.example.mochi_pet.platform.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentBrowserSemanticSnapshotTest {
    @Test
    fun returnsMarkdownAndKeepsRefsActionable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val runtime = AgentBrowserRuntime(context)

        runtime.beginTurn()
        try {
            val initial = withTimeout(30_000) {
                runtime.navigate(
                    operation = "goto",
                    url = "https://example.com",
                )
            }
            assertEquals(
                "mochi-semantic-v2",
                initial["format"]?.jsonPrimitive?.contentOrNull,
            )
            assertTrue(
                initial["markdown"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .contains("Example Domain"),
            )
            val ref = initial["interactive_elements"]
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("ref")
                ?.jsonPrimitive
                ?.contentOrNull
            requireNotNull(ref)

            val clicked = withTimeout(30_000) {
                runtime.click(ref)
            }
            assertEquals(
                "mochi-semantic-v2",
                clicked["format"]?.jsonPrimitive?.contentOrNull,
            )
            assertTrue(
                clicked["snapshot_id"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .startsWith("s"),
            )

            val scrolled = runtime.scroll(
                direction = "down",
                amount = "half_page",
                ref = null,
            )
            assertEquals(
                "mochi-semantic-v2",
                scrolled["format"]?.jsonPrimitive?.contentOrNull,
            )
        } finally {
            runtime.closeTurn()
        }

        assertFalse(runtime.state.value.active)
    }
}
