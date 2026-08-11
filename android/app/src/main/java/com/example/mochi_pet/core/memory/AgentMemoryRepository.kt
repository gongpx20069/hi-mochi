package com.example.mochi_pet.core.memory

import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.database.dao.AgentMemoryDao
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class MemoryContext(
    val recentMessages: List<OpenAiChatMessage>,
    val recentConversation: List<MemoryMessage>,
    val recalledLines: List<String>,
)

data class MemoryMessage(
    val role: String,
    val content: String,
    val createdAt: Instant,
)

interface AgentMemoryRepository {
    suspend fun loadContext(
        query: String,
        recentTurns: Int,
    ): MemoryContext

    suspend fun saveTurn(
        userText: String,
        assistantText: String,
    )
}

class RoomAgentMemoryRepository(
    private val dao: AgentMemoryDao,
    private val clock: Clock = Clock.systemUTC(),
    private val promptZone: ZoneId = ZoneId.systemDefault(),
) : AgentMemoryRepository {
    private var lastStoredAtEpochMillis = 0L

    override suspend fun loadContext(
        query: String,
        recentTurns: Int,
    ): MemoryContext {
        require(recentTurns in 1..50)
        val recent = dao.listRecentMessages(recentTurns * 2)
            .asReversed()
        val recentIds = recent.mapTo(mutableSetOf(), AgentMemoryEntity::id)
        val terms = MemoryLexicalSearch.terms(query)
        val hits = if (terms.isEmpty()) {
            emptyList()
        } else {
            val lookupTerms = MemoryLexicalSearch.candidateLookupTerms(terms)
            MemoryLexicalSearch.rank(
                query = query,
                queryTerms = terms,
                candidates = dao.search(
                    matchQuery = MemoryLexicalSearch.matchQuery(lookupTerms),
                    limit = MAX_SEARCH_CANDIDATES,
                )
                    .asSequence()
                    .filterNot { it.id in recentIds }
                    .toList(),
            )
                .asSequence()
                .take(MAX_MEMORY_HITS)
                .toList()
        }
        val recalled = linkedMapOf<String, AgentMemoryEntity>()
        hits.forEach { hit ->
            dao.listBefore(hit.createdAtEpochMillis, MEMORY_CONTEXT_RADIUS)
                .asReversed()
                .plus(hit)
                .plus(
                    dao.listAfter(
                        hit.createdAtEpochMillis,
                        MEMORY_CONTEXT_RADIUS,
                    ),
                )
                .filterNot { it.id in recentIds }
                .forEach { recalled[it.id] = it }
        }
        val recentConversation = recent.mapNotNull {
            it.toMemoryMessage()
        }
        return MemoryContext(
            recentMessages = recentConversation.map {
                OpenAiChatMessage(
                    role = it.role,
                    content = it.content,
                )
            },
            recentConversation = recentConversation,
            recalledLines = recalled.values
                .sortedBy(AgentMemoryEntity::createdAtEpochMillis)
                .map { it.toPromptLine(promptZone) },
        )
    }

    override suspend fun saveTurn(
        userText: String,
        assistantText: String,
    ) {
        val user = userText.trim()
        val assistant = assistantText.trim()
        if (user.isEmpty() || assistant.isEmpty()) {
            return
        }
        val turnId = UUID.randomUUID().toString()
        val timestamp = synchronized(this) {
            maxOf(clock.millis(), lastStoredAtEpochMillis + 2).also {
                lastStoredAtEpochMillis = it
            }
        }
        dao.insertTurn(
            listOf(
                messageEntity(
                    turnId = turnId,
                    role = "user",
                    content = user,
                    createdAtEpochMillis = timestamp,
                ),
                messageEntity(
                    turnId = turnId,
                    role = "assistant",
                    content = assistant,
                    createdAtEpochMillis = timestamp + 1,
                ),
            ),
        )
    }

    private fun messageEntity(
        turnId: String,
        role: String,
        content: String,
        createdAtEpochMillis: Long,
    ): AgentMemoryEntity {
        val storedContent = content.take(MAX_MEMORY_CONTENT_CHARS)
        return AgentMemoryEntity(
            id = UUID.randomUUID().toString(),
            turnId = turnId,
            role = role,
            type = "${role}_msg",
            content = storedContent,
            searchText = searchableMemoryText(storedContent),
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }
}

private fun AgentMemoryEntity.toMemoryMessage(): MemoryMessage? {
    val messageRole = role ?: return null
    if (messageRole != "user" && messageRole != "assistant") {
        return null
    }
    return MemoryMessage(
        role = messageRole,
        content = content,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    )
}

internal fun searchableMemoryText(value: String): String =
    MemoryLexicalSearch.searchableText(value)

internal fun memoryTerms(value: String): List<String> =
    MemoryLexicalSearch.terms(value)

private fun AgentMemoryEntity.toPromptLine(zoneId: ZoneId): String {
    val speaker = if (role == "assistant") "Mochi" else "User"
    val timestamp = Instant.ofEpochMilli(createdAtEpochMillis)
        .atZone(zoneId)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    return "- [$timestamp] $speaker: $content"
}

private const val MAX_MEMORY_CONTENT_CHARS = 20_000
private const val MAX_SEARCH_CANDIDATES = 100
private const val MAX_MEMORY_HITS = 4
private const val MEMORY_CONTEXT_RADIUS = 3
