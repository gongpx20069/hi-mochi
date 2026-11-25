package com.example.mochi_pet.feature.home

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.settings.ProviderPreset
import com.example.mochi_pet.core.agent.llm.ApiProtocol
import com.example.mochi_pet.core.settings.ProviderProfileInput
import com.example.mochi_pet.core.settings.ProviderProfileSummary
import com.example.mochi_pet.core.settings.ProviderProfilesSummary
import com.example.mochi_pet.core.settings.ProviderSettingsSummary
import com.example.mochi_pet.core.settings.ProviderShareSelection
import com.example.mochi_pet.core.settings.SpeechProfileSummary
import com.example.mochi_pet.core.settings.SpeechProfilesSummary
import com.example.mochi_pet.core.settings.SpeechSettingsSummary
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.wake.WakeRuntimeState
import com.example.mochi_pet.core.wake.WakeKeyword
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderProfilesUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @After fun restore() { Locale.setDefault(locale) }
    private val first = profile("first", "First")
    private val second = profile("second", "Second")

    @Test fun `OpenAI endpoint is filled and selecting Kimi replaces it before saving`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        compose.setContent {
            MaterialTheme {
                ProviderConnectionDialog(null, ProviderSettingsUiState(isLoading = false), {}, { saved = it })
            }
        }
        compose.onNodeWithText("API endpoint").performScrollTo().assertTextContains("https://api.openai.com/v1")
        compose.onNodeWithText("Advanced connection settings").performScrollTo().performClick()
        compose.onNodeWithTag("provider-api-protocol").performScrollTo().assertTextContains("Responses")
        compose.onNode(hasText("OpenAI") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Kimi").performScrollTo().performClick()
        compose.onNodeWithText("API endpoint").performScrollTo().assertTextContains("https://api.moonshot.cn/v1")
        compose.onNodeWithText("Model name").performScrollTo().performTextInput("fixture-model")
        compose.onNodeWithText("API key").performScrollTo().performTextInput("synthetic-key")
        compose.onNodeWithText("Save connection").performClick()
        assertEquals(ProviderPreset.KIMI, saved!!.preset)
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, saved!!.settings.apiProtocol)
        assertEquals("https://api.moonshot.cn/v1", saved!!.settings.endpoint)
    }

    @Test fun `saved connection keeps chat until protocol is explicitly changed without replacing its key`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        compose.setContent {
            MaterialTheme { ProviderConnectionDialog(first, ProviderSettingsUiState(isLoading = false), {}, { saved = it }) }
        }
        compose.onNodeWithText("Advanced connection settings").performScrollTo().performClick()
        compose.onNodeWithTag("provider-api-protocol").performScrollTo().assertTextContains("Chat Completions").performClick()
        compose.onNodeWithText("Responses").performClick()
        compose.onNodeWithText("Save connection").performClick()
        assertEquals(ApiProtocol.RESPONSES, saved!!.settings.apiProtocol)
        assertEquals("", saved!!.settings.apiKeyReplacement)
        assertEquals(first.settings.model, saved!!.settings.model)
    }

    @Test fun `Groq preset saves official chat endpoint and displays quota guidance`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        val opened = mutableListOf<String>()
        renderEditor(onSave = { saved = it }, onOpenUri = opened::add)
        compose.onNode(hasText("OpenAI") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Groq").performScrollTo().performClick()
        compose.onNodeWithText("API endpoint").performScrollTo().assertTextContains("https://api.groq.com/openai/v1")
        compose.onNodeWithText(
            "Free-tier requests and tokens are limited. Check reset times in Groq Console; Mochi does not retry quota errors, switch providers, or upgrade billing automatically.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Model name").performScrollTo().performTextInput("fixture-tool-model")
        compose.onNodeWithText("API key").performScrollTo().performTextInput("synthetic-groq-key")
        for ((label, url) in listOf(
            "Register / sign in to Groq" to "https://console.groq.com/",
            "Create Groq API key" to "https://console.groq.com/keys",
            "Browse Groq models" to "https://console.groq.com/docs/models",
            "Check Groq limits" to "https://console.groq.com/settings/limits",
        )) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(url, opened.last())
        }
        compose.onNodeWithText("Save connection").performClick()
        assertEquals(ProviderPreset.GROQ, saved!!.preset)
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, saved!!.settings.apiProtocol)
        assertEquals("https://api.groq.com/openai/v1", saved!!.settings.endpoint)
        assertEquals("synthetic-groq-key", saved!!.settings.apiKeyReplacement)
    }

    @Test fun `Chinese ModelScope preset exposes token and account requirements and saves chat endpoint`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        var saved: ProviderProfileInput? = null
        val opened = mutableListOf<String>()
        renderEditor(onSave = { saved = it }, onOpenUri = opened::add)
        compose.onNode(hasText("OpenAI") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("魔搭 ModelScope").performScrollTo().performClick()
        compose.onNodeWithText("API 端点").performScrollTo().assertTextContains("https://api-inference.modelscope.cn/v1")
        compose.onNodeWithText("此预设将 Mochi 连接到魔搭托管的 API-Inference 服务。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "1. 注册或登录魔搭，验证邮箱，并绑定已实名认证的阿里云账号以使用 API-Inference。",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "3. 选择当前支持 API 调用的语言模型，另外确认其支持 Tool Calling（工具调用）；只有 API-Inference 标记还不够。从模型页面的当前调用示例复制完整模型 ID，包含组织名前缀。",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "2. 打开访问令牌页面，创建有效期合适的令牌并粘贴到下方。应使用魔搭 Access Token，而不是阿里云 AccessKey 或百炼 DashScope Key。",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("模型名称").performScrollTo().performTextInput("fixture/tool-model")
        compose.onNodeWithText("魔搭 Access Token").performScrollTo().performTextInput("synthetic-modelscope-token")
        for ((label, url) in listOf(
            "魔搭注册与登录教程" to "https://www.modelscope.cn/docs/accounts/registration",
            "阿里云账号绑定指南" to "https://www.modelscope.cn/docs/accounts/aliyun-binding-and-authorization",
            "魔搭访问令牌教程" to "https://www.modelscope.cn/docs/accounts/token",
            "创建魔搭 Access Token" to "https://modelscope.cn/my/myaccesstoken",
            "模型选择与 API 使用限制" to "https://www.modelscope.cn/docs/model-service/API-Inference/limits",
            "查看魔搭模型" to "https://www.modelscope.cn/models",
            "魔粒获取与扣减规则" to "https://www.modelscope.cn/docs/magicube/intro",
            "查看我的魔粒" to "https://modelscope.cn/magicube/usage?tab=earn",
            "打开官方 API 指南" to "https://www.modelscope.cn/docs/model-service/API-Inference/intro",
        )) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(url, opened.last())
        }
        compose.onNodeWithText("保存连接").performClick()
        assertEquals(ProviderPreset.MODELSCOPE, saved!!.preset)
        assertEquals(ApiProtocol.CHAT_COMPLETIONS, saved!!.settings.apiProtocol)
        assertEquals("https://api-inference.modelscope.cn/v1", saved!!.settings.endpoint)
        assertEquals("synthetic-modelscope-token", saved!!.settings.apiKeyReplacement)
        assertEquals("fixture/tool-model", saved!!.settings.model)
    }

    @Test fun `ModelScope documentation links preserve an existing custom endpoint model and key`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        val opened = mutableListOf<String>()
        val profile = first.copy(preset = ProviderPreset.MODELSCOPE, settings = first.settings.copy(
            providerType = ProviderPreset.MODELSCOPE.protocol,
            endpoint = "https://example.com/existing/v1",
            model = "fixture/existing-model",
        ))
        renderEditor(profile = profile, onSave = { saved = it }, onOpenUri = opened::add)
        compose.onNodeWithText("Model selection and API limits").performScrollTo().performClick()
        assertEquals(
            "https://www.modelscope.cn/docs/model-service/API-Inference/limits",
            opened.single(),
        )
        compose.onNodeWithText("Save connection").performClick()
        assertEquals(ProviderPreset.MODELSCOPE, saved!!.preset)
        assertEquals(profile.settings.endpoint, saved!!.settings.endpoint)
        assertEquals(profile.settings.model, saved!!.settings.model)
        assertEquals(profile.settings.apiProtocol, saved!!.settings.apiProtocol)
        assertEquals("", saved!!.settings.apiKeyReplacement)
    }

    @Test fun `Agnes keeps saved configuration and opens current model documentation`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        val opened = mutableListOf<String>()
        val agnes = first.copy(preset = ProviderPreset.AGNES, settings = first.settings.copy(
            providerType = ProviderPreset.AGNES.protocol, endpoint = ProviderPreset.AGNES.endpoint,
            model = "saved-agnes-model",
        ))
        renderEditor(profile = agnes, onSave = { saved = it }, onOpenUri = opened::add)
        compose.onNodeWithText("Current Agnes model guide").performScrollTo().performClick()
        assertEquals("https://wiki.agnes-ai.com/en/docs/agnes-30-flash", opened.last())
        compose.onNodeWithText("Open official API guide").performScrollTo().performClick()
        assertEquals("https://wiki.agnes-ai.com/en/docs/overview", opened.last())
        compose.onNodeWithText("Save connection").performClick()
        assertEquals("saved-agnes-model", saved!!.settings.model)
        assertEquals("", saved!!.settings.apiKeyReplacement)
        assertEquals(agnes.id, saved!!.id)
    }

    @Test fun `unavailable browser shows a safe error without losing entered connection data`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        renderEditor(onSave = { saved = it }, onOpenUri = { throw IllegalArgumentException("fixture") })
        compose.onNode(hasText("OpenAI") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Groq").performScrollTo().performClick()
        compose.onNodeWithText("Model name").performScrollTo().performTextInput("fixture-model")
        compose.onNodeWithText("API key").performScrollTo().performTextInput("synthetic-key")
        compose.onNodeWithText("Create Groq API key").performScrollTo().performClick()
        compose.onNodeWithText("Could not open the official page. Install or enable a browser and try again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save connection").performClick()
        assertEquals("fixture-model", saved!!.settings.model)
        assertEquals("synthetic-key", saved!!.settings.apiKeyReplacement)
    }

    private fun renderEditor(
        profile: ProviderProfileSummary? = null,
        onSave: (ProviderProfileInput) -> Unit,
        onOpenUri: (String) -> Unit,
    ) {
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { onOpenUri(uri) }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    ProviderConnectionDialog(profile, ProviderSettingsUiState(isLoading = false), {}, onSave)
                }
            }
        }
    }

    @Test fun `share import is first and all Settings parts remain reachable at large font`() {
        Locale.setDefault(Locale.ENGLISH)
        render(fontScale = 1.5f)
        compose.onNodeWithTag("settings-parts").performScrollToIndex(0)
        compose.onNodeWithTag("settings-part-Share and import").assertIsDisplayed()
        for (title in listOf("AI connections", "Speech and wake", "Conversation and persona", "Display and language")) {
            compose.onNodeWithTag("settings-parts").performScrollToNode(hasTestTag("settings-part-$title"))
            compose.onNodeWithTag("settings-part-$title").assertIsDisplayed()
        }
    }

    @Test fun `share selection starts with active profile and permits multiple saved accounts`() {
        Locale.setDefault(Locale.ENGLISH)
        var selection: ProviderShareSelection? = null
        render(share = { selection = it })
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Share Providers"))
        compose.onNodeWithText("Share Providers").performClick()
        compose.onNode(hasText("Second") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Share selected").performClick()
        assertEquals(setOf("first", "second"), selection!!.llmProfileIds)
    }

    @Test fun `Chinese Settings shows localized section heading first`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        render()
        compose.onNodeWithText("分享与导入").assertIsDisplayed()
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasTestTag("settings-part-AI connections"))
        compose.onNodeWithText("AI 连接").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [37])
    fun `Android 17 offers explicit local network permission and recovery at large font`() {
        Locale.setDefault(Locale.ENGLISH)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        render(fontScale = 1.5f)
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Allow local network access"))
        compose.onNodeWithText("Allow local network access").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Local network access is off. Allow it before connecting to a local server.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open app permissions").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [37])
    fun `Android 17 settings reflect an existing local network grant`() {
        Locale.setDefault(Locale.ENGLISH)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        render()
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Local network access is allowed."))
        compose.onNodeWithText("Local network access is allowed.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow local network access").assertDoesNotExist()
    }

    @Test fun `speech shares select the active account by default and permit a second account`() {
        Locale.setDefault(Locale.ENGLISH)
        var selection: ProviderShareSelection? = null
        val summary = SpeechSettingsSummary(provider = SpeechProvider.AZURE,
            azureEndpoint = "https://fixture.cognitiveservices.azure.com", hasAzureApiKey = true)
        val profiles = SpeechProfilesSummary(listOf(
            SpeechProfileSummary("voice-one", "Voice One", summary),
            SpeechProfileSummary("voice-two", "Voice Two", summary),
        ), "voice-one")
        render(speechProfiles = profiles, share = { selection = it })
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Share Providers"))
        compose.onNodeWithText("Share Providers").performClick()
        compose.onNode(hasText("Voice Two") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Share selected").performClick()
        assertEquals(setOf("voice-one", "voice-two"), selection!!.speechProfileIds)
    }

    @Test fun `wake phrase selection is visible in speech settings without enabling disabled capture`() {
        Locale.setDefault(Locale.ENGLISH)
        val wake = mutableStateOf(WakeRuntimeState())
        render(fontScale = 1.5f, wake = wake, selectKeyword = {
            wake.value = wake.value.copy(keyword = it ?: WakeKeyword.ENGLISH, keywordOverride = it)
        })
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Follow system"))
        compose.onNodeWithText("Follow system").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Chinese · 老莫老莫"))
        compose.onNodeWithText("Chinese · 老莫老莫").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Say 老莫老莫 hands-free").performScrollTo().assertIsDisplayed()
        assertEquals(WakeCaptureStatus.DISABLED, wake.value.status)
        compose.onNodeWithText("English · Hi Mochi").performScrollTo().performClick().assertIsSelected()
        assertEquals(WakeKeyword.ENGLISH, wake.value.keyword)
        compose.onNodeWithText("Follow system").performScrollTo().performClick().assertIsSelected()
        assertEquals(null, wake.value.keywordOverride)
        assertEquals(WakeCaptureStatus.DISABLED, wake.value.status)
    }

    @Test
    @Config(sdk = [35, 37])
    fun `managed recall opens settings directly at journal card`() {
        Locale.setDefault(Locale.ENGLISH)
        render(focusJournal = true)
        compose.onNodeWithText("Daily journal").assertIsDisplayed()
        compose.onNodeWithText("Also manages Memory Recall for journals and saved conversations.").assertIsDisplayed()
    }

    private fun render(
        fontScale: Float = 1f,
        share: (ProviderShareSelection) -> Unit = {},
        speechProfiles: SpeechProfilesSummary = SpeechProfilesSummary(),
        wake: State<WakeRuntimeState> = mutableStateOf(WakeRuntimeState()),
        selectKeyword: (WakeKeyword?) -> Unit = {},
        focusJournal: Boolean = false,
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    ProviderSettingsSurface(
                        focusJournalSettings = focusJournal,
                        journalSettings = {
                            com.example.mochi_pet.feature.journal.JournalSettingsCard(
                                com.example.mochi_pet.core.journal.JournalSettings(), false, null, onEnabled = {},
                            )
                        },
                        state = ProviderSettingsUiState(summary = first.settings, isLoading = false,
                            profiles = ProviderProfilesSummary(listOf(first, second), first.id)),
                        speechState = SpeechSettingsUiState(summary = speechProfiles.active.settings,
                            profiles = speechProfiles, isLoading = false),
                        providerShareState = ProviderShareUiState(), toolsState = ToolsUiState(),
                        agentSettingsState = AgentSettingsUiState(), personaState = PersonaUiState(),
                        wakeState = wake.value, wakeFeedback = null, onSelectWakeKeyword = selectKeyword,
                        onEnableWake = {}, onDisableWake = {}, onProviderAction = {}, onSpeechAction = {},
                        onCreateProviderShareLink = share, onReceiveProviderShareLink = {},
                        onSetRecentConversationTurns = {}, onSetFocusStandby = { _, _ -> },
                        onSavePersona = { _, _, _ -> },
                    )
                }
            }
        }
    }

    private fun profile(id: String, name: String) = ProviderProfileSummary(
        id, name, ProviderPreset.OPENAI,
        ProviderSettingsSummary(providerType = ProviderPreset.OPENAI.protocol,
            endpoint = ProviderPreset.OPENAI.endpoint, model = "fixture-model", hasApiKey = true),
    )
}
