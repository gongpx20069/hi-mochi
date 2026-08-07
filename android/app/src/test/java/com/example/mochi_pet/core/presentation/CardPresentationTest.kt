package com.example.mochi_pet.core.presentation

import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.UiDirective
import com.example.mochi_pet.core.agent.tool.ToolInputException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CardPresentationTest {
    @Test
    fun `research card binds only retained web evidence`() {
        val card = parseCardDirective(
            arguments = buildJsonObject {
                put("type", "research_summary")
                put("placement", "inline")
                put(
                    "sources",
                    buildJsonArray { add(JsonPrimitive("web_search")) },
                )
            },
            evidence = listOf(
                CardToolEvidence(
                    toolName = "browser_navigate",
                    data = buildJsonObject {
                        put(
                            "results",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("title", "Official source")
                                        put("url", "https://example.com/source")
                                        put("snippet", "Evidence")
                                        put("source", "bing")
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
            reply = "The evidence supports the answer.",
        )

        requireNotNull(card)
        assertEquals(CardPlacement.INLINE, card.placement)
        assertEquals("The evidence supports the answer.", card.body)
        assertEquals("Official source", card.sources.single().title)
    }

    @Test
    fun `evidence card is omitted when required tool result is absent`() {
        val card = parseCardDirective(
            arguments = buildJsonObject {
                put("type", "agenda_timeline")
            },
            evidence = emptyList(),
            reply = "No retained calendar evidence.",
        )

        assertNull(card)
    }

    @Test
    fun `content card uses selected MCP evidence and bounded fields`() {
        val card = parseCardDirective(
            arguments = buildJsonObject {
                put("title", "Project notes")
                put("body", "The latest decision is ready.")
                put(
                    "evidence_tools",
                    buildJsonArray {
                        add(JsonPrimitive("notion_search"))
                    },
                )
                put(
                    "items",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("title", "Decision")
                                put("detail", "Use a general content card.")
                            },
                        )
                    },
                )
            },
            evidence = listOf(
                CardToolEvidence(
                    toolName = "notion_search",
                    data = buildJsonObject {
                        put("title", "Project page")
                        put("url", "https://notion.so/project")
                    },
                ),
            ),
            reply = "Spoken fallback.",
        )

        requireNotNull(card)
        assertEquals(CardType.CONTENT, card.type)
        assertEquals("The latest decision is ready.", card.body)
        assertEquals("Decision", card.items.single().title)
        assertEquals("Project page", card.sources.single().title)
    }

    @Test
    fun `content card rejects missing selected evidence`() {
        val card = parseCardDirective(
            arguments = buildJsonObject {
                put("type", "content")
                put(
                    "evidence_tools",
                    buildJsonArray {
                        add(JsonPrimitive("tencent_docs_get_content"))
                    },
                )
            },
            evidence = listOf(
                CardToolEvidence(
                    toolName = "notion_search",
                    data = buildJsonObject {
                        put("url", "https://notion.so/project")
                    },
                ),
            ),
            reply = "No matching evidence.",
        )

        assertNull(card)
    }

    @Test
    fun `auto card is inline in conversation`() {
        val resolved = CardPresentationPolicy().resolve(
            card = presentation(CardPlacement.AUTO),
            currentSurface = MochiSurface.Conversation,
            uiDirective = null,
        )

        assertEquals(CardPlacement.INLINE, resolved.placement)
    }

    @Test
    fun `inline request becomes Home card while Home is active`() {
        val resolved = CardPresentationPolicy().resolve(
            card = presentation(CardPlacement.INLINE),
            currentSurface = MochiSurface.Face,
            uiDirective = null,
        )

        assertEquals(CardPlacement.HOME, resolved.placement)
    }

    @Test
    fun `explicit navigation defers a competing card`() {
        val resolved = CardPresentationPolicy().resolve(
            card = presentation(CardPlacement.HOME),
            currentSurface = MochiSurface.Conversation,
            uiDirective = UiDirective(surface = "today"),
        )

        assertEquals(CardPlacement.DEFERRED, resolved.placement)
    }

    @Test
    fun `settings protects against automatic Home card navigation`() {
        val resolved = CardPresentationPolicy().resolve(
            card = presentation(CardPlacement.AUTO),
            currentSurface = MochiSurface.Settings,
            uiDirective = null,
        )

        assertEquals(CardPlacement.DEFERRED, resolved.placement)
    }

    @Test
    fun `tools protects against automatic Home card navigation`() {
        val resolved = CardPresentationPolicy().resolve(
            card = presentation(CardPlacement.AUTO),
            currentSurface = MochiSurface.Tools,
            uiDirective = null,
        )

        assertEquals(CardPlacement.DEFERRED, resolved.placement)
    }

    @Test
    fun `invalid explicit placement rejects optional card`() {
        assertThrows(ToolInputException::class.java) {
            parseCardDirective(
                arguments = buildJsonObject {
                    put("type", "insight")
                    put("placement", "deferred")
                },
                evidence = emptyList(),
                reply = "Text remains valid.",
            )
        }
    }

    @Test
    fun `multiple results from one tool remain available`() {
        val evidence = listOf("one", "two").map { suffix ->
            CardToolEvidence(
                toolName = "browser_navigate",
                data = buildJsonObject {
                    put(
                        "results",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("title", "Source $suffix")
                                    put("url", "https://example.com/$suffix")
                                    put("source", "bing")
                                },
                            )
                        },
                    )
                },
            )
        }

        val card = parseCardDirective(
            arguments = buildJsonObject {
                put("type", "research_summary")
            },
            evidence = evidence,
            reply = "Two sources.",
        )

        assertEquals(2, card?.sources?.size)
    }

    @Test
    fun `inline placement preserves the same card content as Home`() {
        val card = CardPresentationPolicy().resolve(
            card = CardPresentation(
                type = CardType.RESEARCH_SUMMARY,
                placement = CardPlacement.AUTO,
                title = "Research",
                body = "Evidence-backed conclusion.",
            ),
            currentSurface = MochiSurface.Conversation,
            uiDirective = null,
        )

        assertEquals(CardPlacement.INLINE, card.placement)
        assertEquals("Evidence-backed conclusion.", card.body)
    }

    @Test
    fun `todo action target is bound from retained evidence`() {
        val card = parseCardDirective(
            arguments = buildJsonObject {
                put("type", "todo_focus")
                put(
                    "actions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "complete_todo")
                                put("target_id", "todo_1")
                            },
                        )
                    },
                )
            },
            evidence = listOf(
                CardToolEvidence(
                    toolName = "manage_mochi_todo",
                    data = buildJsonObject {
                        put(
                            "todos",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", "todo_1")
                                        put("content", "Finish card actions")
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
            reply = "One task remains.",
        )

        requireNotNull(card)
        assertEquals("todo_1", card.actions.single().targetId)
    }

    @Test
    fun `source action cannot escape retained evidence`() {
        assertThrows(ToolInputException::class.java) {
            parseCardDirective(
                arguments = buildJsonObject {
                    put("type", "research_summary")
                    put(
                        "actions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "open_source")
                                    put("source_index", 1)
                                },
                            )
                        },
                    )
                },
                evidence = listOf(
                    CardToolEvidence(
                        toolName = "browser_navigate",
                        data = buildJsonObject {
                            put(
                                "results",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("title", "Only source")
                                            put(
                                                "url",
                                                "https://example.com/source",
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    ),
                ),
                reply = "One source.",
            )
        }
    }

    @Test
    fun `card accepts at most three requested actions`() {
        assertThrows(ToolInputException::class.java) {
            parseCardDirective(
                arguments = buildJsonObject {
                    put("type", "insight")
                    put(
                        "actions",
                        buildJsonArray {
                            repeat(4) {
                                add(
                                    buildJsonObject {
                                        put("type", "open_talk")
                                    },
                                )
                            }
                        },
                    )
                },
                evidence = emptyList(),
                reply = "Too many actions.",
            )
        }
    }

    private fun presentation(placement: CardPlacement) =
        CardPresentation(
            type = CardType.INSIGHT,
            placement = placement,
            title = "Insight",
        )
}
