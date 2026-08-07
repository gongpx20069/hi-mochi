package com.example.mochi_pet.platform.browser

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMarkdownTest {
    @Test
    fun `formats semantic blocks as bounded markdown`() {
        val result = formatBrowserMarkdown(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("kind", "heading")
                        put("level", 2)
                        put("text", "Results")
                    },
                )
                add(
                    buildJsonObject {
                        put("kind", "list_item")
                        put("text", "First result")
                    },
                )
                add(
                    buildJsonObject {
                        put("kind", "table_row")
                        put(
                            "cells",
                            buildJsonArray {
                                add(JsonPrimitive("Name"))
                                add(JsonPrimitive("Value"))
                            },
                        )
                    },
                )
            },
        )

        assertEquals(
            "## Results\n\n- First result\n\n" +
                "| Name | Value |\n| --- | --- |",
            result.content,
        )
        assertFalse(result.truncated)
    }

    @Test
    fun `marks markdown truncated at configured limit`() {
        val result = formatBrowserMarkdown(
            blocks = buildJsonArray {
                add(
                    buildJsonObject {
                        put("kind", "paragraph")
                        put("text", "A".repeat(100))
                    },
                )
            },
            maxChars = 20,
        )

        assertEquals(20, result.content.length)
        assertTrue(result.truncated)
    }
}
