package com.example.mochi_pet.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCenterSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun taskCenterAndDiagnosticsAreReachableWithoutStartingAProbe() {
        val app = compose.activity.application as MochiApplication
        val before = runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadSummary() }
        compose.onNodeWithText(localizeUiText("Tasks")).performClick()
        compose.onNodeWithText(localizeUiText("Task center")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Check setup")).performClick()
        compose.onNodeWithText(localizeUiText("Run configuration check")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Checking")).assertDoesNotExist()
        compose.onNodeWithText(localizeUiText("Back")).performClick()
        compose.onNodeWithText(localizeUiText("Close")).performClick()
        compose.onNodeWithText(localizeUiText("Tasks")).assertIsDisplayed()
        val after = runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadSummary() }
        assertEquals(before, after)
    }
}
