package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

data class AgentSettings(
    val recentConversationTurns: Int = DEFAULT_RECENT_CONVERSATION_TURNS,
    val focusStandbyEnabled: Boolean = DEFAULT_FOCUS_STANDBY_ENABLED,
    val focusStandbyDelaySeconds: Int =
        DEFAULT_FOCUS_STANDBY_DELAY_SECONDS,
)

interface AgentSettingsRepository {
    suspend fun load(): AgentSettings

    suspend fun setRecentConversationTurns(turns: Int): AgentSettings

    suspend fun setFocusStandby(
        enabled: Boolean,
        delaySeconds: Int,
    ): AgentSettings
}

class DataStoreAgentSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : AgentSettingsRepository {
    override suspend fun load(): AgentSettings {
        val preferences = dataStore.data.first()
        return AgentSettings(
            recentConversationTurns = preferences[RECENT_TURNS]
                ?.coerceIn(
                    MIN_RECENT_CONVERSATION_TURNS,
                    MAX_RECENT_CONVERSATION_TURNS,
                )
                ?: DEFAULT_RECENT_CONVERSATION_TURNS,
            focusStandbyEnabled =
                preferences[FOCUS_STANDBY_ENABLED]
                    ?: DEFAULT_FOCUS_STANDBY_ENABLED,
            focusStandbyDelaySeconds =
                preferences[FOCUS_STANDBY_DELAY_SECONDS]
                    ?.takeIf(ALLOWED_FOCUS_STANDBY_DELAYS_SECONDS::contains)
                    ?: DEFAULT_FOCUS_STANDBY_DELAY_SECONDS,
        )
    }

    override suspend fun setRecentConversationTurns(
        turns: Int,
    ): AgentSettings {
        require(
            turns in MIN_RECENT_CONVERSATION_TURNS..
                MAX_RECENT_CONVERSATION_TURNS,
        ) {
            "Recent conversation turns must be between " +
                "$MIN_RECENT_CONVERSATION_TURNS and " +
                MAX_RECENT_CONVERSATION_TURNS
        }
        dataStore.edit { preferences ->
            preferences[RECENT_TURNS] = turns
        }
        return load()
    }

    override suspend fun setFocusStandby(
        enabled: Boolean,
        delaySeconds: Int,
    ): AgentSettings {
        require(delaySeconds in ALLOWED_FOCUS_STANDBY_DELAYS_SECONDS) {
            "Focus standby delay must be one of " +
                ALLOWED_FOCUS_STANDBY_DELAYS_SECONDS.joinToString()
        }
        dataStore.edit { preferences ->
            preferences[FOCUS_STANDBY_ENABLED] = enabled
            preferences[FOCUS_STANDBY_DELAY_SECONDS] = delaySeconds
        }
        return load()
    }

    private companion object {
        val RECENT_TURNS = intPreferencesKey("agent.recent_conversation_turns")
        val FOCUS_STANDBY_ENABLED =
            booleanPreferencesKey("display.focus_standby_enabled")
        val FOCUS_STANDBY_DELAY_SECONDS =
            intPreferencesKey("display.focus_standby_delay_seconds")
    }
}

const val DEFAULT_RECENT_CONVERSATION_TURNS = 20
const val MIN_RECENT_CONVERSATION_TURNS = 1
const val MAX_RECENT_CONVERSATION_TURNS = 50
const val DEFAULT_FOCUS_STANDBY_ENABLED = true
const val DEFAULT_FOCUS_STANDBY_DELAY_SECONDS = 30
val ALLOWED_FOCUS_STANDBY_DELAYS_SECONDS = setOf(30, 60, 120, 300, 600)
