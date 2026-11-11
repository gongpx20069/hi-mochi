package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import com.example.mochi_pet.core.database.entity.DailyJournalEntity

@Dao
interface MemoryRecallDao {
    @Query("""SELECT m.* FROM agent_memories m JOIN agent_memory_fts f ON f.memory_id = m.id
        WHERE f.search_text MATCH :query AND m.created_at_epoch_millis >= :start
        AND m.created_at_epoch_millis < :end AND m.type IN ('user_msg', 'assistant_msg')
        ORDER BY m.created_at_epoch_millis DESC, m.id LIMIT :limit OFFSET :offset""")
    suspend fun searchConversations(query: String, start: Long, end: Long, limit: Int, offset: Int): List<AgentMemoryEntity>

    @Query("""SELECT * FROM agent_memories WHERE created_at_epoch_millis >= :start
        AND created_at_epoch_millis < :end AND type IN ('user_msg', 'assistant_msg')
        ORDER BY created_at_epoch_millis DESC, id LIMIT :limit OFFSET :offset""")
    suspend fun conversations(start: Long, end: Long, limit: Int, offset: Int): List<AgentMemoryEntity>

    @Query("""SELECT j.* FROM daily_journals j JOIN journal_search s ON s.date = j.date
        WHERE s.search_text MATCH :query AND j.date >= :start AND j.date < :end
        AND j.status != 'DELETED' AND j.text != '' ORDER BY j.date DESC LIMIT :limit OFFSET :offset""")
    suspend fun searchJournals(query: String, start: String, end: String, limit: Int, offset: Int): List<DailyJournalEntity>

    @Query("""SELECT * FROM daily_journals WHERE date >= :start AND date < :end
        AND status != 'DELETED' AND text != '' ORDER BY date DESC LIMIT :limit OFFSET :offset""")
    suspend fun journals(start: String, end: String, limit: Int, offset: Int): List<DailyJournalEntity>

    @Query("SELECT * FROM agent_memories WHERE id = :id AND type IN ('user_msg', 'assistant_msg')")
    suspend fun message(id: String): AgentMemoryEntity?

    @Query("""SELECT * FROM agent_memories WHERE type IN ('user_msg', 'assistant_msg')
        AND (created_at_epoch_millis < :time OR (created_at_epoch_millis = :time AND id < :id))
        ORDER BY created_at_epoch_millis DESC, id DESC LIMIT 1""")
    suspend fun before(time: Long, id: String): AgentMemoryEntity?

    @Query("""SELECT * FROM agent_memories WHERE type IN ('user_msg', 'assistant_msg')
        AND (created_at_epoch_millis > :time OR (created_at_epoch_millis = :time AND id > :id))
        ORDER BY created_at_epoch_millis, id LIMIT 1""")
    suspend fun after(time: Long, id: String): AgentMemoryEntity?

    @Query("""SELECT * FROM agent_memories WHERE turn_id = :turnId AND type IN ('user_msg', 'assistant_msg')
        ORDER BY created_at_epoch_millis, id LIMIT 33""")
    suspend fun turn(turnId: String): List<AgentMemoryEntity>
}
