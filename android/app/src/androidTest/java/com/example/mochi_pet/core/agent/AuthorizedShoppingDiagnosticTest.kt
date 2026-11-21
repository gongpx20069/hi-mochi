package com.example.mochi_pet.core.agent

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.createAgentRunner
import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthorizedShoppingDiagnosticTest {
    @Test fun documentWriteGuardRejectsOtherFiles() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            requireDocumentTarget("tencent_docs_smartcanvas_edit", """{"file_id":"other"}""", "created")
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            requireDocumentTarget("tencent_docs_smartcanvas_edit", """{"file_id":"created"}""", null)
        }
        requireDocumentTarget("tencent_docs_smartcanvas_edit", """{"file_id":"created"}""", "created")
    }

    private fun requireDocumentTarget(name: String, arguments: String, documentId: String?) {
        if (name != "tencent_docs_smartcanvas_edit") return
        val args = Json.parseToJsonElement(arguments).jsonObject
        check(documentId != null && args["file_id"]?.jsonPrimitive?.content == documentId) {
            "Only the explicitly identified document created by this shopping task may be edited"
        }
    }

    @Test fun inspectShoppingPrerequisites() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiAuthorizedSwitchShopping") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        val registry = com.example.mochi_pet.core.agent.tool.ToolRegistry(app.appToolRuntime.tools(
            com.example.mochi_pet.core.extensions.ExtensionToolScope.FOREGROUND_MAIN,
            app.toolCatalogRepository::isBuiltInEnabled,
        ))
        val result = registry.execute("app_read", "{}", ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation))
        val data = result.data as? JsonObject
        val taobao = data?.get("apps")?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["packageName"]?.jsonPrimitive?.content == "com.taobao.taobao"
        }
        app.cacheDir.resolve("shopping-prerequisites.json").writeText(buildJsonObject {
            put("status", result.status)
            put("taobao_selected", taobao != null)
            taobao?.get("canOpen")?.let { put("can_open", it) }
            data?.get("accessibilityGranted")?.let { put("accessibility_granted", it) }
            data?.get("assistanceAvailable")?.let { put("assistance_available", it) }
        }.toString())
    }

    @Test fun researchSwitchAndSaveDocument() = runBlocking<Unit>(Dispatchers.Default) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiAuthorizedSwitchShopping") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        val destination = app.cacheDir.resolve("authorized-shopping-results.json")
        destination.writeText("""{"status":"connecting_ui_automation"}""")
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        destination.writeText("""{"status":"loading_provider"}""")
        val config = app.providerSettingsRepository.loadRuntimeConfig().forForegroundRequest()
        val profiles = app.providerSettingsRepository.loadProfiles()
        val results = linkedMapOf<String, JsonObject>()
        val modelMs = mutableListOf<Long>()
        var taobaoId: String? = null
        var blockedAppCall: String? = null
        val browserMode = InstrumentationRegistry.getArguments().getString("mochiShoppingBrowser") == "true"
        val recoverDocument = InstrumentationRegistry.getArguments().getString("mochiRecoverShoppingDocument") == "true"
        val documentId = InstrumentationRegistry.getArguments().getString("mochiShoppingDocumentId")
        var writableDocumentId = documentId
        val documentReaders = setOf(
            "tencent_docs_manage_search_file", "tencent_docs_smartcanvas_read", "tencent_docs_get_content", "tencent_docs_smartcanvas_get_top_level_pages",
            "tencent_docs_smartcanvas_get_page_info", "tencent_docs_smartcanvas_get_element_info", "tencent_docs_smartcanvas_find",
        )
        var browserElements = emptyMap<String, String>()
        val forbiddenCommerce = Regex("立即购买|立即支付|付款|提交订单|结算|领券|开通会员|联系卖家|buy now|pay now|checkout|place order", RegexOption.IGNORE_CASE)
        var requestCount = 0
        val started = SystemClock.elapsedRealtime()
        fun save(status: String, reply: String? = null) = destination.writeText(buildJsonObject {
            put("status", status)
            put("model", config.model)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("model_request_count", requestCount)
            put("taobao_discovered", taobaoId != null)
            put("browser_mode", browserMode)
            blockedAppCall?.let { put("blocked_app_call", it) }
            put("tool_results", JsonArray(results.values.toList()))
            put("model_ms", JsonArray(modelMs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            reply?.let { put("reply", it) }
        }.toString())
        val allowed = (if (recoverDocument) setOf(
            "load_skill", "tool_search", "tencent_docs_manage_search_file",
        ) else setOf(
            "load_skill", "tool_search", "app_read", "app_navigate", "app_click", "app_input", "app_scroll",
            if (documentId == null) "tencent_docs_create_smartcanvas_by_mdx" else "tencent_docs_smartcanvas_edit",
            "tencent_docs_smartcanvas_edit",
        )) + documentReaders + if (browserMode && !recoverDocument) setOf("browser_read", "browser_navigate", "browser_click", "browser_input", "browser_scroll", "request_user_assistance") else emptySet()
        val client = object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                request.messages.filter { it.role == "tool" }.forEach { message ->
                    val id = checkNotNull(message.toolCallId)
                    if (id in results) return@forEach
                    val envelope = Json.parseToJsonElement(checkNotNull(message.content)).jsonObject
                    val data = envelope["data"] as? JsonObject
                    if (message.name == "tencent_docs_create_smartcanvas_by_mdx" && envelope["status"]?.jsonPrimitive?.content == "ok") {
                        writableDocumentId = (data?.get("structured_content") as? JsonObject)?.get("file_id")?.jsonPrimitive?.content
                    }
                    if (message.name?.startsWith("browser_") == true) {
                        data?.get("interactive_elements")?.jsonArray?.let { elements ->
                            browserElements = elements.associate { element ->
                                val item = element.jsonObject
                                item.getValue("ref").jsonPrimitive.content to item.getValue("name").jsonPrimitive.content
                            }
                        }
                    }
                    if (message.name == "app_read") {
                        data?.get("apps")?.jsonArray?.map { it.jsonObject }?.singleOrNull {
                            it["packageName"]?.jsonPrimitive?.content == "com.taobao.taobao"
                        }?.let { taobaoId = it.getValue("id").jsonPrimitive.content }
                    }
                    results[id] = buildJsonObject {
                        put("tool", message.name)
                        put("status", envelope.getValue("status"))
                        envelope["code"]?.let { put("code", it) }
                        if (message.name == "app_navigate") data?.get("returnedToMochi")?.let { put("returnedToMochi", it) }
                        if (message.name?.startsWith("tencent_docs_") == true) data?.let { put("document_evidence", it) }
                    }
                }
                save("running")
                check(++requestCount <= 60) { "Shopping model-request budget exhausted" }
                val before = SystemClock.elapsedRealtime()
                val response = try { app.openAiChatClient.complete(config, request) } finally {
                    modelMs += SystemClock.elapsedRealtime() - before
                }
                response.choices.single().message.toolCalls.orEmpty().forEach { call ->
                    check(call.type == "function" && call.function.name in allowed) {
                        "Shopping boundary blocked ${call.function.name}; no sibling calls dispatched"
                    }
                    requireDocumentTarget(call.function.name, call.function.arguments, writableDocumentId)
                    if (call.function.name.startsWith("app_")) {
                        val args = Json.parseToJsonElement(call.function.arguments).jsonObject
                        val id = args["app_id"]?.jsonPrimitive?.content
                        if (!((call.function.name == "app_read" && id == null) || (taobaoId != null && id == taobaoId))) {
                            blockedAppCall = "${call.function.name}: discovered=${taobaoId != null}, matching_id=${id == taobaoId}"
                        }
                        check((call.function.name == "app_read" && id == null) || (taobaoId != null && id == taobaoId)) {
                            "Only the discovered user-authorized Taobao app may be operated"
                        }
                    }
                    if (call.function.name == "browser_click") {
                        val args = Json.parseToJsonElement(call.function.arguments).jsonObject
                        val label = browserElements[args["ref"]?.jsonPrimitive?.content]
                        check(label != null && !forbiddenCommerce.containsMatchIn(label)) {
                            "Unobserved or forbidden commerce control; no sibling calls dispatched"
                        }
                    }
                }
                return response
            }
        }
        save("launching_activity")
        ActivityScenario.launch(MainActivity::class.java).use {
            save("creating_runner")
            val runner = app.createAgentRunner(
                sink = { error("Shopping must not change Mochi navigation") },
                observer = AgentPipelineObserver { _, _ -> },
                onWeatherLoaded = { error("Shopping must not access device location") },
                includeBrowser = browserMode, includeBrowserInteractions = browserMode,
                includeAgentLink = false, taskTitle = "Authorized Switch 2 comparison", chatClient = client,
            )
            save("running")
            if (browserMode) app.agentBrowserRuntime.beginTurn(wait = false)
            try {
                val reply = withTimeout(12 * 60_000L) {
                    runner.run(AgentRunRequest(
                        provider = config,
                        query = if (recoverDocument) {
                            "只读核查：刚才这个任务创建了一份腾讯智能文档，标题为“Switch 2 非日版全新主机比价”。" +
                                "按这个准确标题查找，读取匹配文档并报告其链接、实际内容、已记录价格和商品来源。" +
                                "若有多个匹配，分别列出链接和创建时间，不要自行选择修改。不要创建、编辑或删除任何文档，不要访问淘宝。"
                        } else (if (browserMode) "使用Mochi内置浏览器访问淘宝网站，不使用淘宝App或无障碍。"
                            else "打开淘宝App。") + "比较全新一手、非日版 Nintendo Switch 2 主机的实际在售价格。" +
                            "尽量检查至少五个候选商品，排除二手、翻新、日版、配件、定金和虚假低价规格。" +
                            "记录可核实的版本、规格、价格、运费或优惠条件和商品链接（地址指商品链接，不是我的收货地址）。" +
                            (if (documentId == null) "把有证据的结果写入一份新建的腾讯智能文档，标题为“Switch 2 非日版全新主机比价”，并读回核实。"
                                else "继续本任务刚创建的腾讯智能文档，file_id=$documentId，标题为“Switch 2 非日版全新主机比价”。先读其当前内容，将有证据的结果更新到这份文档并读回核实，绝不新建副本。") +
                            "将已核实的最低价合格商品加入购物车一次，然后检查购物车是否存在该商品。" +
                            "绝不下单、付款、领券、开通会员、联系卖家或修改本任务之外的文档。不要获取个人收货地址。" +
                            "使用已启用工具与已有登录；不要改变设置或绕过权限。没有证据不得编造价格、链接、全网最低或成功状态。" +
                            (if (browserMode) "必要的网页登录或验证码可以请求用户在浏览器手动完成，不获取密码或验证码。"
                                else "必要的登录、授权或不可访问界面请停止并明确说明，不要等待用户输入。完成或受阻后返回Mochi。") +
                            "这是已授权的真实任务，不是模拟。最终分别报告比价范围、文档链接和加购状态。",
                        currentEmotion = "neutral",
                        context = ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation),
                    ))
                }
                save("finished", reply.reply)
            } catch (error: Exception) {
                save("failed", error.javaClass.simpleName)
                throw error
            } finally {
                if (browserMode) app.agentBrowserRuntime.closeTurn()
                check(profiles == app.providerSettingsRepository.loadProfiles()) { "Provider settings changed" }
            }
        }
    }
}
