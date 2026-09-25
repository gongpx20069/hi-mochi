package com.example.mochi_pet.feature.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.ProviderProtocolException
import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.diagnostics.CheckStatus
import com.example.mochi_pet.core.diagnostics.ConfigurationCheck
import com.example.mochi_pet.core.diagnostics.ConfigurationProbe
import com.example.mochi_pet.core.diagnostics.RepairTarget
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.settings.ProviderSettingsRepository
import com.example.mochi_pet.core.skills.requiredToolNames
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import com.example.mochi_pet.core.tools.skillReadiness

internal fun configurationProbes(app: MochiApplication): List<ConfigurationProbe> = listOf(
    ConfigurationProbe("model", "AI Provider", RepairTarget.MODEL) {
        listOf(checkModelProvider(app.providerSettingsRepository, app.openAiChatClient))
    },
    ConfigurationProbe("speech", "Speech recognition and synthesis", RepairTarget.SPEECH) {
        val speech = app.speechSettingsRepository.loadSummary()
        val voice = app.voiceRuntime.state.value
        val microphone = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        listOf(
            ConfigurationCheck("microphone", "Microphone permission",
                if (microphone) CheckStatus.PASSED else CheckStatus.ATTENTION,
                if (microphone) "Microphone permission is granted. No audio was recorded."
                else "Allow microphone access in Android app permissions.", RepairTarget.PERMISSIONS),
            ConfigurationCheck("recognition", "Speech recognition",
                if (!speech.isReady || (speech.provider == SpeechProvider.SYSTEM && !voice.recognitionAvailable)) {
                    CheckStatus.ATTENTION
                } else CheckStatus.NOT_TESTED,
                when {
                    !speech.isReady -> "Complete and save the selected speech provider credentials."
                    speech.provider == SpeechProvider.SYSTEM && !voice.recognitionAvailable ->
                        "No Android recognition service is available. Install one or configure cloud speech."
                    else -> "Configuration is available. Use the microphone to verify real recognition and cloud access."
                }, RepairTarget.SPEECH),
            ConfigurationCheck("synthesis", "Speech synthesis",
                if ((!speech.synthesisEnabled && !voice.ttsReady) || !speech.isReady) {
                    CheckStatus.ATTENTION
                } else CheckStatus.NOT_TESTED,
                if (!speech.synthesisEnabled && !voice.ttsReady) {
                    "Android speech output is not ready. Check the installed engine and voice data."
                } else {
                    "Use voice preview in Settings to verify the selected voice, playback and service quota."
                }, RepairTarget.SPEECH),
        )
    },
    ConfigurationProbe("extensions", "Extensions", RepairTarget.TOOLS) {
        val catalog = app.toolCatalogRepository.loadSummary()
        val link = catalog.agentLink
        listOf(
            extensionCheck("termux", "Termux", catalog.termux, RepairTarget.TERMUX),
            extensionCheck("mijia", "Mi Home", catalog.mijia, RepairTarget.MIJIA),
            ConfigurationCheck("agentlink", "AgentLink", when {
                !link.installed -> CheckStatus.DISABLED
                !link.authorized || !link.connected -> CheckStatus.ATTENTION
                !link.enabled -> CheckStatus.DISABLED
                else -> CheckStatus.PASSED
            }, when {
                !link.installed -> "Optional integration is not installed."
                !link.authorized -> "Open AgentLink and authorize Mochi in its native access page."
                !link.connected -> "Open AgentLink and check the desktop Bridge connection."
                !link.enabled -> "Connected but disabled. Enable only if you want Agents to use it."
                else -> "Connection is available. No remote task was started."
            }, RepairTarget.AGENTLINK),
        )
    },
    ConfigurationProbe("skills", "Skills and Tools", RepairTarget.SKILLS) {
        val catalog = app.toolCatalogRepository.loadSummary()
        app.skillRepository.listSkills().map { skill ->
            val readiness = catalog.skillReadiness(skill.requiredToolNames)
            ConfigurationCheck("skill:${skill.id}", skill.name, when {
                !skill.enabled -> CheckStatus.DISABLED
                readiness.isReady -> CheckStatus.PASSED
                else -> CheckStatus.ATTENTION
            }, when {
                !skill.enabled -> "Skill is disabled; no changes are needed unless you want to use it."
                readiness.isReady -> "Required Tools are available. External service calls were not tested."
                else -> "Connect and enable the missing groups and their required Tool switches."
            }, if (readiness.isReady || !skill.enabled) RepairTarget.SKILLS else RepairTarget.TOOLS,
                missingRequirements = if (skill.enabled) readiness.missingRequirements.toList() else emptyList())
        }
    },
)

internal suspend fun checkModelProvider(
    settings: ProviderSettingsRepository,
    client: OpenAiChatClient,
): ConfigurationCheck {
    if (!settings.loadSummary().isReady) {
        return ConfigurationCheck("model", "AI Provider", CheckStatus.ATTENTION,
            "Save the endpoint, model and API key in Settings.", RepairTarget.MODEL)
    }
    val config = settings.loadRuntimeConfig()
    val response = client.complete(config, OpenAiChatRequest(
        model = config.model,
        messages = listOf(OpenAiChatMessage(role = "user", content = "Reply with OK only.")),
    ))
    val message = response.choices.firstOrNull()?.message
    if (message?.role != "assistant" || message.content.isNullOrBlank() ||
        !message.toolCalls.isNullOrEmpty()
    ) throw ProviderProtocolException("Invalid diagnostic response")
    return ConfigurationCheck("model", "AI Provider", CheckStatus.PASSED,
        "A fixed-text model request succeeded. Tool calling and image input were not tested.")
}

internal fun extensionCheck(
    id: String,
    title: String,
    provider: ExtensionProviderSummary,
    repair: RepairTarget,
): ConfigurationCheck = ConfigurationCheck(id, title, when {
    !provider.installed -> CheckStatus.DISABLED
    !provider.trusted || !provider.connected -> CheckStatus.ATTENTION
    !provider.enabled -> CheckStatus.DISABLED
    else -> CheckStatus.PASSED
}, when {
    !provider.installed -> "Optional integration is not installed."
    !provider.trusted -> "Install the extension from the same signing channel as Mochi. Do not uninstall to change channels."
    !provider.connected && repair == RepairTarget.TERMUX ->
        "Open Configure Termux to check installation, Android command permission, external access and the Shell helper."
    !provider.connected -> "Open extension setup and complete connection and device selection."
    !provider.enabled -> "Connected but disabled. Enable only if you want Agents to use it."
    else -> "The extension reports connected. Device actions and Shell execution were not tested."
}, repair)
