package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiChoice
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnglishToolSchemasTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = OpenAiProviderConfig(endpoint = "https://example.invalid", apiKey = "unused", model = "fixture")

    @Test fun `English annotations and Chinese protocol literals need no model or cache`() = runBlocking {
        val source = schema("Read the record")
        val resolver = resolver { error("English schema must not contact a model") }
        assertEquals(source, resolver.prepareSchema(source))
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `Skill preparation keeps English paragraphs unchanged and caches isolated translations`() = runBlocking {
        val requests = mutableListOf<OpenAiChatRequest>()
        val resolver = resolver { request ->
            requests += request
            translated(request)
        }
        val english = "# Example\n\nUse `browser_read` and https://example.invalid."
        assertEquals(english, resolver.prepareInstruction(english))
        assertTrue(requests.isEmpty())
        val mixed = "$english\n\n请先读取页面。"
        val prepared = resolver.prepareInstruction(mixed)
        assertEquals("$english\n\nEnglish annotation", prepared)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertTrue(request.tools.isEmpty())
        assertFalse(request.messages.last().content!!.contains("https://example.invalid"))
        assertEquals(prepared, resolver.prepareInstruction(mixed))
        assertEquals(1, requests.size)
        resolver.prepareInstruction("$english\n\n请检查页面。")
        assertEquals(2, requests.size)
    }

    @Test fun `isolated translation preserves all protocol fields and reuses a source keyed cache`() = runBlocking {
        val requests = mutableListOf<OpenAiChatRequest>()
        val client: suspend (OpenAiChatRequest) -> String = { request ->
            requests += request
            translated(request)
        }
        val source = schema("读取记录", nestedDescription = "需要读取的记录")
        val prepared = resolver(client).prepareSchema(source)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertTrue(request.tools.isEmpty())
        assertFalse(request.messages.last().content!!.contains("协议默认值"))
        assertFalse(request.messages.last().content!!.contains("协议枚举值"))
        val replacements = ToolSchemaLanguage.texts(source).filterValues { !ToolSchemaLanguage.isEnglishText(it) }
            .mapValues { "English annotation" }
        assertEquals(ToolSchemaLanguage.replace(source, replacements), prepared)
        assertEquals(source.getValue("function").jsonObject.getValue("parameters").jsonObject.getValue("required"),
            prepared.getValue("function").jsonObject.getValue("parameters").jsonObject.getValue("required"))
        ToolSchemaLanguage.requireEnglish(prepared)
        assertEquals(prepared, resolver(client).prepareSchema(source))
        assertEquals(1, requests.size)
        resolver(client).prepareSchema(schema("读取不同记录"))
        assertEquals(2, requests.size)
        assertEquals("读取记录", source.getValue("function").jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test fun `schema node traversal distinguishes field names from keywords and preserves literal objects`() = runBlocking {
        val source = Json.parseToJsonElement("""
            {"type":"function","function":{"name":"fixture","description":"English","parameters":{
              "type":"object","properties":{
                "default":{"type":"string","description":"参数说明"},
                "a/b~c":{"oneOf":[{"type":"string","title":"标题"}]},
                "payload":{"default":{"description":"协议默认值"},"examples":[{"title":"协议示例"}]}
              },"${'$'}defs":{"record":{"description":"定义说明"}},
              "enum":[{"description":"协议枚举值"}],"required":["default","a/b~c"]
            }}}
        """).jsonObject
        val texts = ToolSchemaLanguage.texts(source)
        assertEquals(setOf("/function/description", "/function/parameters/properties/default/description",
            "/function/parameters/properties/a~1b~0c/oneOf/0/title", "/function/parameters/\$defs/record/description"),
            texts.keys)
        val result = resolver(::translated).prepareSchema(source)
        assertEquals(ToolSchemaLanguage.replace(source,
            texts.filterValues { !ToolSchemaLanguage.isEnglishText(it) }.mapValues { "English annotation" }), result)
    }

    @Test fun `missing extra duplicate or untranslated entries fail without publishing a cache`() = runBlocking {
        for (invalid in listOf(
            """{"translations":[]}""",
            """{"translations":[{"id":"unexpected","text":"English"}]}""",
            """{"translations":[{"id":"/function/description","text":"仍然中文"}]}""",
            """{"translations":[{"id":"/function/description","text":"English"},{"id":"/function/description","text":"Duplicate"}]}""",
            """{"translations":[{"id":"/function/description","text":"English","schema":{}}]}""",
            "not JSON",
        )) {
            assertThrows(ToolSchemaTranslationException::class.java) {
                runBlocking { resolver { invalid }.prepareSchema(schema("读取记录")) }
            }
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `one isolated repair rewrites only annotations with remaining non Latin text`() = runBlocking {
        val requests = mutableListOf<OpenAiChatRequest>()
        val resolver = resolver { request ->
            requests += request
            if (requests.size == 1) {
                """{"translations":[{"id":"/function/description","text":"Read the 状态 column"},{"id":"/function/parameters/properties/状态/description","text":"Record value"}]}"""
            } else {
                """{"translations":[{"id":"/function/description","text":"Read the status column"}]}"""
            }
        }
        val prepared = resolver.prepareSchema(schema("读取记录", "记录值"))
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals(listOf("system", "user"), it.messages.map { message -> message.role })
            assertTrue(it.tools.isEmpty())
        }
        val repairRows = Json.parseToJsonElement(requests.last().messages.last().content!!)
            .jsonObject.getValue("translations").jsonArray
        assertEquals(1, repairRows.size)
        assertEquals("Read the 状态 column", repairRows.single().jsonObject.getValue("text").jsonPrimitive.content)
        ToolSchemaLanguage.requireEnglish(prepared)
        assertEquals(prepared, resolver.prepareSchema(schema("读取记录", "记录值")))
        assertEquals(2, requests.size)
    }

    @Test fun `cancelled translation cannot publish a schema or leave a temporary cache`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = resolver { request ->
            started.complete(Unit)
            release.await()
            translated(request)
        }
        val pending = async { resolver.prepareSchema(schema("读取记录")) }
        started.await()
        pending.cancelAndJoin()
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `invalid cache is evicted with an explicit error and an explicit retry regenerates it`() = runBlocking {
        var calls = 0
        val resolver = resolver { request ->
            calls++
            translated(request)
        }
        val source = schema("读取记录")
        val prepared = resolver.prepareSchema(source)
        for (invalid in listOf("invalid JSON", """{"translations":[]}""", " ".repeat(1_048_577))) {
            folder.root.listFiles()!!.single().writeText(invalid)
            val before = calls
            assertThrows(ToolSchemaTranslationException::class.java) {
                runBlocking { resolver.prepareSchema(source) }
            }
            assertEquals(before, calls)
            assertTrue(folder.root.listFiles().orEmpty().isEmpty())
            assertEquals(prepared, resolver.prepareSchema(source))
            assertEquals(before + 1, calls)
        }
    }

    @Test fun `model view retains original execution arguments context and results`() = runBlocking {
        val arguments = buildJsonObject { put("状态", "协议枚举值") }
        val context = ToolExecutionContext(LocalDate.of(2026, 7, 31), MochiSurface.Face)
        val expected = ToolResultEnvelope.success(arguments)
        var calls = 0
        val original = object : AgentTool {
            override val name = "fixture"
            override val schema = schema("读取记录")
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
                assertEquals("协议枚举值", arguments.getValue("状态").jsonPrimitive.content)
                assertEquals(MochiSurface.Face, context.currentSurface)
                calls++
                return expected
            }
        }
        val prepared = resolver(::translated).prepare(listOf(original)).single()
        assertEquals(0, calls)
        assertEquals(original.name, prepared.name)
        ToolSchemaLanguage.requireEnglish(prepared.schema)
        assertSame(expected, prepared.execute(arguments, context))
        assertEquals(1, calls)
    }

    private fun resolver(reply: suspend (OpenAiChatRequest) -> String) = EnglishToolSchemas(
        object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest) =
                OpenAiChatResponse(listOf(OpenAiChoice(OpenAiChatMessage("assistant", reply(request)))))
        },
        { config },
        folder.root,
    )

    private fun translated(request: OpenAiChatRequest): String {
        val rows = Json.parseToJsonElement(request.messages.last().content!!).jsonObject.getValue("translations").jsonArray
        return buildJsonObject {
            put("translations", JsonArray(rows.map { row ->
                buildJsonObject {
                    put("id", row.jsonObject.getValue("id"))
                    put("text", "English annotation")
                }
            }))
        }.toString()
    }

    private fun schema(description: String, nestedDescription: String = "Record value"): JsonObject =
        functionToolSchema("fixture", description, buildJsonObject {
            put("状态", buildJsonObject {
                put("type", "string")
                put("description", nestedDescription)
                put("enum", JsonArray(listOf(JsonPrimitive("协议枚举值"))))
                put("default", "协议默认值")
            })
        }, listOf("状态"))
}
