package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSettingsRepositoryTest {
    @Test
    fun `recent conversation turns default to twenty and persist`() =
        runBlocking {
            val repository = DataStoreAgentSettingsRepository(
                TestPreferencesDataStore(),
            )

            assertEquals(20, repository.load().recentConversationTurns)
            assertEquals(true, repository.load().focusStandbyEnabled)
            assertEquals(
                30,
                repository.load().focusStandbyDelaySeconds,
            )
            assertEquals(
                35,
                repository.setRecentConversationTurns(35)
                    .recentConversationTurns,
            )
        }

    @Test
    fun `recent conversation turns enforce bounds`() {
        val repository = DataStoreAgentSettingsRepository(
            TestPreferencesDataStore(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setRecentConversationTurns(0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setRecentConversationTurns(51) }
        }
    }

    @Test
    fun `focus standby defaults persist and enforce delay choices`() =
        runBlocking {
            val repository = DataStoreAgentSettingsRepository(
                TestPreferencesDataStore(),
            )

            val saved = repository.setFocusStandby(
                enabled = false,
                delaySeconds = 300,
            )

            assertEquals(false, saved.focusStandbyEnabled)
            assertEquals(300, saved.focusStandbyDelaySeconds)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.setFocusStandby(
                        enabled = true,
                        delaySeconds = 45,
                    )
                }
            }
            Unit
        }
}

private class TestPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences =
        transform(state.value).also { state.value = it }
}
