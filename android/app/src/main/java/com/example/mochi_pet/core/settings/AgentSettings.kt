package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

data class AgentSettings(
    val recentConversationTurns: Int = DEFAULT_RECENT_CONVERSATION_TURNS,
)

interface AgentSettingsRepository {
    suspend fun load(): AgentSettings

    suspend fun setRecentConversationTurns(turns: Int): AgentSettings
}

class DataStoreAgentSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : AgentSettingsRepository {
    override suspend fun load(): AgentSettings =
        AgentSettings(
            recentConversationTurns = dataStore.data.first()[RECENT_TURNS]
                ?.coerceIn(
                    MIN_RECENT_CONVERSATION_TURNS,
                    MAX_RECENT_CONVERSATION_TURNS,
                )
                ?: DEFAULT_RECENT_CONVERSATION_TURNS,
        )

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

    private companion object {
        val RECENT_TURNS = intPreferencesKey("agent.recent_conversation_turns")
    }
}

const val DEFAULT_RECENT_CONVERSATION_TURNS = 20
const val MIN_RECENT_CONVERSATION_TURNS = 1
const val MAX_RECENT_CONVERSATION_TURNS = 50
