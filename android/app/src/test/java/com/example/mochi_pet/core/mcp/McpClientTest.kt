package com.example.mochi_pet.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    @Test
    fun `notion aliases match readable official tool names`() {
        assertEquals(
            "notion_search",
            mcpToolAlias(NOTION_SERVER_ID, "notion-search"),
        )
    }

    @Test
    fun `Tencent Docs aliases preserve readable dotted tool names`() {
        assertEquals(
            "tencent_docs_smartcanvas_update_element",
            mcpToolAlias(
                TENCENT_DOCS_SERVER_ID,
                "smartcanvas.update_element",
            ),
        )
    }

    @Test
    fun `manual aliases remain bounded and distinguish long remote names`() {
        val first = mcpToolAlias(
            "manual-server",
            "shared-very-long-tool-name-that-only-differs-at-the-end-a",
        )
        val second = mcpToolAlias(
            "manual-server",
            "shared-very-long-tool-name-that-only-differs-at-the-end-b",
        )

        assertTrue(first.length <= 64)
        assertTrue(first.startsWith("mcp_manual-server_"))
        assertNotEquals(first, second)
    }
}
