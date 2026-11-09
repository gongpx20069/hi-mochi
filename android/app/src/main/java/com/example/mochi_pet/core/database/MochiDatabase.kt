package com.example.mochi_pet.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.mochi_pet.core.database.dao.CalendarEventDao
import com.example.mochi_pet.core.database.dao.AgentMemoryDao
import com.example.mochi_pet.core.database.dao.TodoDao
import com.example.mochi_pet.core.database.dao.SkillDao
import com.example.mochi_pet.core.database.dao.AgentScheduleDao
import com.example.mochi_pet.core.database.entity.CalendarEventEntity
import com.example.mochi_pet.core.database.entity.AgentMemoryFtsEntity
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import com.example.mochi_pet.core.database.entity.TodoEntity
import com.example.mochi_pet.core.database.entity.SkillEntity
import com.example.mochi_pet.core.database.entity.AgentScheduleEntity
import com.example.mochi_pet.core.database.entity.AgentScheduleRunEntity
import com.example.mochi_pet.core.database.entity.PlannerReminderReceiptEntity
import com.example.mochi_pet.core.memory.MemoryLexicalSearch
import com.example.mochi_pet.core.database.dao.JournalDao
import com.example.mochi_pet.core.database.entity.DailyJournalEntity
import com.example.mochi_pet.core.database.entity.JournalSettingsEntity

@Database(
    entities = [
        CalendarEventEntity::class,
        TodoEntity::class,
        SkillEntity::class,
        AgentMemoryEntity::class,
        AgentMemoryFtsEntity::class,
        AgentScheduleEntity::class,
        AgentScheduleRunEntity::class,
        PlannerReminderReceiptEntity::class,
        DailyJournalEntity::class,
        JournalSettingsEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class MochiDatabase : RoomDatabase() {
    abstract fun calendarEventDao(): CalendarEventDao

    abstract fun todoDao(): TodoDao

    abstract fun skillDao(): SkillDao

    abstract fun agentMemoryDao(): AgentMemoryDao

    abstract fun agentScheduleDao(): AgentScheduleDao

    abstract fun journalDao(): JournalDao

    companion object {
        const val DATABASE_NAME = "mochi.db"

        fun create(context: Context): MochiDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MochiDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_journals ADD COLUMN conversationCount INTEGER")
                db.execSQL("ALTER TABLE daily_journals ADD COLUMN userScheduleCount INTEGER")
                db.execSQL("ALTER TABLE daily_journals ADD COLUMN taskCount INTEGER")
                db.execSQL("ALTER TABLE daily_journals ADD COLUMN generatedAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_memories ADD COLUMN source_run_id TEXT")
                db.execSQL("""
                    CREATE TABLE daily_journals (
                        date TEXT NOT NULL PRIMARY KEY, timezone TEXT NOT NULL, status TEXT NOT NULL,
                        title TEXT NOT NULL, text TEXT NOT NULL, error TEXT, updatedAt INTEGER NOT NULL, claim TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE journal_settings (id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, since TEXT)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skills` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `source_url` TEXT NOT NULL,
                        `upstream_version` TEXT,
                        `upstream_digest` TEXT NOT NULL,
                        `local_digest` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `modified` INTEGER NOT NULL,
                        `update_available` INTEGER NOT NULL,
                        `installed_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `last_checked_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_skills_source` " +
                        "ON `skills` (`source`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_skills_enabled` " +
                        "ON `skills` (`enabled`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_memories` (
                        `id` TEXT NOT NULL,
                        `turn_id` TEXT,
                        `role` TEXT,
                        `type` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `search_text` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_agent_memories_turn_id` " +
                        "ON `agent_memories` (`turn_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_memories_type` " +
                        "ON `agent_memories` (`type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_agent_memories_created_at_epoch_millis` " +
                        "ON `agent_memories` (`created_at_epoch_millis`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_schedules` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `run_at_epoch_millis` INTEGER,
                        `local_time` TEXT,
                        `days_of_week` TEXT NOT NULL,
                        `interval_minutes` INTEGER,
                        `timezone` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `next_run_at_epoch_millis` INTEGER,
                        `last_result` TEXT,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_agent_schedules_enabled` " +
                        "ON `agent_schedules` (`enabled`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_agent_schedules_next_run_at_epoch_millis` " +
                        "ON `agent_schedules` (`next_run_at_epoch_millis`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `agent_memory_fts`
                    USING FTS4(
                        `memory_id` TEXT NOT NULL,
                        `search_text` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                val insert = db.compileStatement(
                    """
                    INSERT INTO `agent_memory_fts` (`memory_id`, `search_text`)
                    VALUES (?, ?)
                    """.trimIndent(),
                )
                try {
                    db.query(
                        """
                        SELECT `id`, `content`
                        FROM `agent_memories`
                        ORDER BY `created_at_epoch_millis` ASC
                        """.trimIndent(),
                    ).use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow("id")
                        val contentColumn =
                            cursor.getColumnIndexOrThrow("content")
                        while (cursor.moveToNext()) {
                            insert.clearBindings()
                            insert.bindString(1, cursor.getString(idColumn))
                            insert.bindString(
                                2,
                                MemoryLexicalSearch.searchableText(
                                    cursor.getString(contentColumn),
                                ),
                            )
                            insert.executeInsert()
                        }

                    }
                } finally {
                    insert.close()
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN completed_at_epoch_millis INTEGER")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN series_id TEXT REFERENCES calendar_events(id) ON DELETE CASCADE")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN occurrence_start_at_epoch_millis INTEGER")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE todos ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX index_calendar_events_series_id ON calendar_events(series_id)")
                db.execSQL("ALTER TABLE agent_schedules ADD COLUMN ends_at_epoch_millis INTEGER")
                db.execSQL("ALTER TABLE agent_schedules ADD COLUMN max_runs INTEGER")
                db.execSQL("ALTER TABLE agent_schedules ADD COLUMN planned_runs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE agent_schedules ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE agent_schedules SET last_result = 'INTERRUPTED',
                        enabled = CASE WHEN type = 'ONCE' THEN 0 ELSE enabled END
                    WHERE enabled = 1 AND next_run_at_epoch_millis IS NULL
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE agent_schedule_runs (
                        id TEXT NOT NULL PRIMARY KEY, schedule_id TEXT NOT NULL,
                        revision INTEGER NOT NULL, name TEXT NOT NULL, prompt TEXT NOT NULL,
                        manual INTEGER NOT NULL, planned_at INTEGER, state TEXT NOT NULL,
                        created_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER,
                        output TEXT, error TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_agent_schedule_runs_schedule_id_created_at ON agent_schedule_runs(schedule_id, created_at)")
                db.execSQL("CREATE TABLE planner_reminder_receipts (id TEXT NOT NULL PRIMARY KEY, deliveredAt INTEGER NOT NULL)")
            }
        }
    }
}
