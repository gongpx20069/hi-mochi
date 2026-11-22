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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class AuthorizedShoppingDiagnosticTest {
    @Test fun inspectMarketplaceSearchActivation() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiAuthorizedSwitchShopping") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime = app.agentBrowserRuntime
            runtime.beginTurn(wait = false)
            try {
                runtime.navigate("goto", "https://www.taobao.com/", mode = "desktop", waitSeconds = 10)
                runtime.read(waitSeconds = 5)
                if (InstrumentationRegistry.getArguments().getString("mochiShoppingAssistVerification") == "true") {
                    app.cacheDir.resolve("shopping-verification-status.json").writeText("""{"status":"awaiting_user"}""")
                    val answer = runtime.requestAssistance(
                        "淘宝网页出现安全验证，请在手机浏览器中手动完成；完成后点击“已完成”。不要在聊天中发送密码或验证码。",
                        browser = true, options = emptyList(),
                    )
                    app.cacheDir.resolve("shopping-verification-status.json").writeText(buildJsonObject {
                        put("status", answer.outcome)
                    }.toString())
                    check(answer.outcome == "completed") { "Website verification handoff was not completed" }
                    runtime.read(waitSeconds = 5)
                }
                val page = runtime.ensureWebView()
                val encoded = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<String> { continuation ->
                        page.evaluateJavascript("""
                            (() => {
                              const path = value => { try { const u = new URL(value, document.baseURI); return u.origin + u.pathname; } catch { return ''; } };
                              return JSON.stringify({
                                base_target: document.querySelector('base[target]')?.getAttribute('target') || null,
                                forms: [...document.forms].map(f => ({action:path(f.action), target:f.target, method:f.method})),
                                search_inputs: [...document.forms].filter(f => path(f.action) === 'https://s.taobao.com/search')
                                  .flatMap(f => [...f.elements].filter(e => e instanceof HTMLInputElement && ['text','search'].includes(e.type)))
                                  .map(e => {
                                    e.scrollIntoView({block:'center', inline:'nearest'});
                                    const r = e.getBoundingClientRect(), s = getComputedStyle(e);
                                    const points = [[r.left+r.width/2,r.top+r.height/2], [r.left+2,r.top+r.height/2],
                                      [r.right-2,r.top+r.height/2], [r.left+r.width/2,r.top+2], [r.left+r.width/2,r.bottom-2]];
                                    return {type:e.type, readonly:e.readOnly, disabled:e.matches(':disabled'),
                                      width:r.width, height:r.height, viewport_width:innerWidth, viewport_height:innerHeight,
                                      display:s.display, visibility:s.visibility, opacity:s.opacity,
                                      hits:points.map(([x,y]) => { const h = document.elementFromPoint(x,y);
                                        return {tag:h?.tagName, is_input:h === e, position:h ? getComputedStyle(h).position : null,
                                          verification_mask:/baxia|captcha|verify/i.test(h?.className || '')}; })};
                                  }),
                                frames: [...document.querySelectorAll('iframe')].map(f => ({
                                  visible:!!(f.getBoundingClientRect().width && f.getBoundingClientRect().height),
                                  verification:/baxia|captcha|verify|punish/i.test(f.src || ''),
                                  login:/login|passport/i.test(f.src || '')
                                })),
                                search_buttons: [...document.querySelectorAll('button,input[type=submit]')]
                                  .filter(b => /搜索/.test(b.textContent || b.getAttribute('aria-label') || ''))
                                  .map(b => ({tag:b.tagName, type:b.type, form_target:b.form?.target,
                                    form_action:b.form ? path(b.form.action) : null,
                                    submitter_target:b.getAttribute('formtarget'), inline_handler:b.hasAttribute('onclick')}))
                              });
                            })()
                        """.trimIndent()) { if (continuation.isActive) continuation.resume(it) }
                    }
                }
                app.cacheDir.resolve("shopping-search-activation.json").writeText(
                    Json.parseToJsonElement(encoded).jsonPrimitive.content,
                )
            } finally {
                runtime.closeTurn()
            }
        }
    }

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
        val cartOnly = InstrumentationRegistry.getArguments().getString("mochiShoppingCartOnly") == "true"
        val cartAcceptance = InstrumentationRegistry.getArguments().getString("mochiShoppingCartAcceptance") == "true"
        val recoverDocument = InstrumentationRegistry.getArguments().getString("mochiRecoverShoppingDocument") == "true"
        check(!cartOnly || (browserMode && !recoverDocument))
        check(!cartAcceptance || cartOnly)
        val documentId = InstrumentationRegistry.getArguments().getString("mochiShoppingDocumentId")
        var writableDocumentId = documentId
        val documentReaders = setOf(
            "tencent_docs_manage_search_file", "tencent_docs_smartcanvas_read", "tencent_docs_get_content", "tencent_docs_smartcanvas_get_top_level_pages",
            "tencent_docs_smartcanvas_get_page_info", "tencent_docs_smartcanvas_get_element_info", "tencent_docs_smartcanvas_find",
        )
        var browserElements = emptyMap<String, String>()
        val clickedControls = mutableMapOf<String, String>()
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
            put("cart_acceptance", cartAcceptance)
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
        val permitted = if (cartOnly) allowed.filterNot { it.startsWith("tencent_docs_") || it.startsWith("app_") }.toSet() else allowed
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
                        if (message.name?.startsWith("browser_") == true) data?.let {
                            put("browser_evidence", JsonObject(it.filterKeys { key ->
                                key in setOf("url", "title", "loading", "snapshot_available", "snapshot_fresh",
                                    "action_pending", "action_outcome", "observation_status", "page_error")
                            }))
                        }
                        clickedControls[id]?.let { put("clicked_control", it) }
                    }
                }
                save("running")
                requestCount++
                val before = SystemClock.elapsedRealtime()
                val response = try { app.openAiChatClient.complete(config, request) } finally {
                    modelMs += SystemClock.elapsedRealtime() - before
                }
                response.choices.single().message.toolCalls.orEmpty().forEach { call ->
                    check(call.type == "function" && call.function.name in permitted) {
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
                        clickedControls[call.id] = label
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
                        } else if (cartOnly) {
                            "继续用户已授权的真实购物任务，本次只完成商品核验与加购，不读写文档。" +
                                "使用Mochi内置浏览器访问淘宝，搜索全新一手、非日版Nintendo Switch 2整机。" +
                                "比较当前可核验的候选价格，检查版本、成色、实际SKU与运费，排除配件、二手、日版、定金和虚假低价规格。" +
                                "只将已核实的候选中最低价合格商品加入购物车一次，并读购物车核实商品和SKU；若相同SKU已经存在，不重复添加。" +
                                "不声称未经证实的全网最低。绝不下单、付款、领券、开通会员、联系卖家或读取个人收货地址。" +
                                "不使用淘宝App，不改变设置。若必须登录或完成验证码，立即请求用户在浏览器协助，不获取密码或验证码、不绕过验证。" +
                                "页面操作未确认时只能继续读取，不重复点击。没有证据不得声称核验或加购成功，最终报告实际状态与阻碍。" +
                                (if (cartAcceptance) {
                                    "本次是限定范围的浏览器功能验收：只检查两个候选详情，然后收束，不扩大搜索。" +
                                        "整个阶段使用正常Agent工具预算，包括Skill加载和页面观察；请预留购物车查重、加购与回读所需步骤。" +
                                        "只在两个候选中选择有充分证据的合格商品；若均无法核实则明确报告，绝不降低商品条件。" +
                                        "最终明确说明此次只检查两个候选，不代表完整比价任务已经完成。"
                                } else "")
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
