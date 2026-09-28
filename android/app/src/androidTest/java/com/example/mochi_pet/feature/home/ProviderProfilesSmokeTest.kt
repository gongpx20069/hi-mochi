package com.example.mochi_pet.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderProfilesSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearTestScreenFlag() {
        compose.runOnUiThread { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun savedConnectionsRemainReadableAndSettingsStartsWithSharing() {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val repo = app.providerSettingsRepository
        val speech = app.speechSettingsRepository
        val speechBefore = runBlocking(Dispatchers.IO) {
            speech.loadProfiles().also { catalog ->
                if (catalog.active.settings.isReady) {
                    val config = speech.loadRuntimeConfig()
                    assertTrue("Stored speech credentials must decrypt", when (config) {
                        is com.example.mochi_pet.core.settings.SpeechRuntimeConfig.System -> true
                        is com.example.mochi_pet.core.settings.SpeechRuntimeConfig.IFlytek ->
                            config.apiKey.isNotBlank() && config.apiSecret.isNotBlank()
                        is com.example.mochi_pet.core.settings.SpeechRuntimeConfig.Azure -> config.apiKey.isNotBlank()
                    })
                }
            }
        }
        val before = runBlocking(Dispatchers.IO) {
            repo.loadProfiles().also { catalog ->
                if (catalog.active?.settings?.isReady == true) {
                    assertTrue("Stored active credential must decrypt", repo.loadRuntimeConfig().apiKey.isNotBlank())
                }
            }
        }
        if (compose.onAllNodesWithText(localizeUiText("Settings")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(localizeUiText("Settings")).performClick()
        }
        compose.onNodeWithTag("settings-part-Share and import").assertIsDisplayed()
        compose.onNodeWithTag("settings-parts")
            .performScrollToNode(hasTestTag("settings-part-AI connections"))
        compose.onNodeWithText(localizeUiText("AI connections")).assertIsDisplayed()
        compose.onNodeWithTag("settings-parts")
            .performScrollToNode(hasTestTag("settings-part-Speech and wake"))
        compose.onNode(hasText(localizeUiText("Edit")) and
            hasAnyAncestor(hasTestTag("speech-profile-${speechBefore.activeId}"))).performScrollTo().performClick()
        compose.onNodeWithText(localizeUiText("Edit speech connection")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Cancel")).performClick()
        assertEquals(before, runBlocking(Dispatchers.IO) { repo.loadProfiles() })
        assertEquals(speechBefore, runBlocking(Dispatchers.IO) { speech.loadProfiles() })
    }
}
