package com.example.mochi_pet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import com.example.mochi_pet.core.database.entity.AgentScheduleRunEntity
import com.example.mochi_pet.core.database.entity.DailyJournalEntity
import com.example.mochi_pet.core.database.entity.JournalSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_settings WHERE id = 0")
    suspend fun settings(): JournalSettingsEntity?

    @Query("SELECT * FROM journal_settings WHERE id = 0")
    fun observeSettings(): Flow<JournalSettingsEntity?>

    @Upsert
    suspend fun saveSettings(settings: JournalSettingsEntity)

    @Query("SELECT * FROM daily_journals WHERE date = :date")
    suspend fun get(date: String): DailyJournalEntity?

    @Query("SELECT * FROM daily_journals WHERE date = :date")
    fun observe(date: String): Flow<DailyJournalEntity?>

    @Query("SELECT date FROM daily_journals WHERE date >= :start AND date < :end AND status != 'DELETED'")
    fun observeMonth(start: String, end: String): Flow<List<String>>

    @Query("SELECT * FROM daily_journals WHERE status IN ('WAITING', 'GENERATING') ORDER BY date DESC")
    suspend fun pending(): List<DailyJournalEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DailyJournalEntity)

    @Query("UPDATE daily_journals SET status = 'FAILED', error = 'Journal automation is off', claim = NULL, updatedAt = :now WHERE status IN ('WAITING', 'GENERATING')")
    suspend fun stopPending(now: Long)

    @Transaction
    suspend fun setEnabled(enabled: Boolean, date: String, now: Long) {
        val previous = settings()
        if (previous?.enabled == enabled) return
        saveSettings(JournalSettingsEntity(enabled = enabled, since = if (enabled) date else previous?.since))
        if (!enabled) stopPending(now)
    }

    @Transaction
    suspend fun prepare(entries: List<DailyJournalEntity>, since: String) {
        val settings = settings() ?: return
        if (!settings.enabled || settings.since != since) return
        entries.forEach { insert(it) }
    }

    @Query("UPDATE daily_journals SET status = 'GENERATING', error = NULL, claim = :claim, updatedAt = :now WHERE date = :date AND (status = 'WAITING' OR (status = 'GENERATING' AND claim = :claim)) AND EXISTS (SELECT 1 FROM journal_settings WHERE id = 0 AND enabled = 1)")
    suspend fun claim(date: String, claim: String, now: Long): Int

    @Query("UPDATE daily_journals SET status = :status, title = :title, text = :text, error = :error, claim = NULL, updatedAt = :now WHERE date = :date AND status = 'GENERATING' AND claim = :claim AND EXISTS (SELECT 1 FROM journal_settings WHERE id = 0 AND enabled = 1)")
    suspend fun finish(date: String, claim: String, status: String, title: String, text: String, error: String?, now: Long): Int

    @Query("UPDATE daily_journals SET status = :status, title = :title, text = :text, error = NULL, claim = NULL, updatedAt = :now, generatedAt = :now, conversationCount = :conversations, userScheduleCount = :schedules, taskCount = :tasks WHERE date = :date AND status = 'GENERATING' AND claim = :claim AND EXISTS (SELECT 1 FROM journal_settings WHERE id = 0 AND enabled = 1)")
    suspend fun publish(date: String, claim: String, status: String, title: String, text: String, now: Long,
        conversations: Int, schedules: Int, tasks: Int): Int

    @Query("UPDATE daily_journals SET status = 'WAITING', claim = NULL, error = NULL, updatedAt = :now WHERE date = :date AND status NOT IN ('DELETED', 'GENERATING') AND EXISTS (SELECT 1 FROM journal_settings WHERE id = 0 AND enabled = 1)")
    suspend fun retry(date: String, now: Long): Int

    @Query("UPDATE daily_journals SET status = 'DELETED', title = '', text = '', error = NULL, claim = NULL, updatedAt = :now, generatedAt = NULL, conversationCount = NULL, userScheduleCount = NULL, taskCount = NULL WHERE date = :date")
    suspend fun delete(date: String, now: Long)

    @Query("UPDATE daily_journals SET status = 'FAILED', error = 'Journal generation was interrupted', claim = NULL, updatedAt = :now WHERE date = :date AND status = 'GENERATING' AND updatedAt = :expectedTime")
    suspend fun interrupt(date: String, expectedTime: Long, now: Long)

    @Query("UPDATE daily_journals SET status = 'FAILED', error = 'Automatic catch-up covers the last seven days. Retry this day manually.', claim = NULL, updatedAt = :now WHERE date = :date AND status IN ('WAITING', 'GENERATING')")
    suspend fun expire(date: String, now: Long)

    @Query("SELECT * FROM agent_memories WHERE created_at_epoch_millis >= :start AND created_at_epoch_millis < :end AND source_run_id IS NULL AND type IN ('user_msg', 'assistant_msg') ORDER BY created_at_epoch_millis, id LIMIT 100 OFFSET :offset")
    suspend fun conversations(start: Long, end: Long, offset: Int): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_schedule_runs WHERE created_at < :end AND (created_at >= :start OR finished_at >= :start OR finished_at IS NULL) ORDER BY created_at, id")
    suspend fun runs(start: Long, end: Long): List<AgentScheduleRunEntity>
}
