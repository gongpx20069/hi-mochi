package com.example.mochi_pet.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestApiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearTestScreenFlag() {
        compose.runOnUiThread { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun restAndMcpEditorsAreReachableWithoutChangingConnectionsOrSendingRequests() {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val before = runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadProfiles() }
        val speechBefore = runBlocking(Dispatchers.IO) { app.speechSettingsRepository.loadProfiles() }
        compose.onAllNodesWithText(localizeUiText("Tools")).onFirst().performClick()
        compose.onNodeWithTag("tools-list").performScrollToNode(hasText(localizeUiText("Add tool")))
        compose.onNodeWithText(localizeUiText("Add tool")).performClick()
        compose.onNodeWithText(localizeUiText("Connect MCP service")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Connect REST API")).performClick()
        compose.onNodeWithText(localizeUiText("REST API connection")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Connection name")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Save connection")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Close")).performClick()
        compose.onNodeWithText(localizeUiText("Add tool")).performClick()
        compose.onNodeWithText(localizeUiText("Connect MCP service")).performClick()
        compose.onNodeWithText(localizeUiText("Add MCP server")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Cancel")).performClick()
        assertEquals(before, runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadProfiles() })
        assertEquals(speechBefore, runBlocking(Dispatchers.IO) { app.speechSettingsRepository.loadProfiles() })
    }
}
