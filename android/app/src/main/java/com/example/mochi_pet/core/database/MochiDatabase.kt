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
import com.example.mochi_pet.core.memory.MemoryLexicalSearch

@Database(
    entities = [
        CalendarEventEntity::class,
        TodoEntity::class,
        SkillEntity::class,
        AgentMemoryEntity::class,
        AgentMemoryFtsEntity::class,
        AgentScheduleEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MochiDatabase : RoomDatabase() {
    abstract fun calendarEventDao(): CalendarEventDao

    abstract fun todoDao(): TodoDao

    abstract fun skillDao(): SkillDao

    abstract fun agentMemoryDao(): AgentMemoryDao

    abstract fun agentScheduleDao(): AgentScheduleDao

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
                )
                .build()

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
    }
}
