package com.example.mochi_pet.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MochiDatabaseMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath(DATABASE_NAME)
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `migration 4 to 5 rebuilds memory FTS with current tokenizer`() {
        createDatabaseFromSchema(version = 4).apply {
            execSQL(
                """
                INSERT INTO agent_memories (
                    id,
                    turn_id,
                    role,
                    type,
                    content,
                    search_text,
                    created_at_epoch_millis
                ) VALUES (
                    'legacy-memory',
                    'legacy-turn',
                    'user',
                    'user_msg',
                    'Mochi上海旅行',
                    ' 上 海 上海 ',
                    1
                )
                """.trimIndent(),
            )
            close()
        }

        val database = Room.databaseBuilder(
            context,
            MochiDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(MochiDatabase.MIGRATION_4_5, MochiDatabase.MIGRATION_5_6, MochiDatabase.MIGRATION_6_7, MochiDatabase.MIGRATION_7_8, MochiDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase.query(
                """
                SELECT memory_id, search_text
                FROM agent_memory_fts
                WHERE search_text MATCH '"mochi"'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-memory", cursor.getString(0))
                assertTrue(cursor.getString(1).contains(" mochi "))
            }
            database.openHelper.writableDatabase.query("SELECT content, source_run_id FROM agent_memories").use {
                assertTrue(it.moveToFirst())
                assertEquals("Mochi上海旅行", it.getString(0))
                assertTrue(it.isNull(1))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `all released schemas migrate with planner records and indefinite schedules preserved`() {
        for (version in 1..8) {
            context.deleteDatabase(DATABASE_NAME)
            createDatabaseFromSchema(version).apply {
                execSQL("""INSERT INTO calendar_events
                    (id,title,description,start_at_epoch_millis,end_at_epoch_millis,all_day,timezone_id,recurrence_rule,location,reminder_at_epoch_millis,created_at_epoch_millis,updated_at_epoch_millis)
                    VALUES ('event','Keep',NULL,1000,2000,0,'UTC','FREQ=DAILY',NULL,NULL,1,1)""")
                execSQL("""INSERT INTO todos
                    (id,content,status,priority,scheduled_date_epoch_day,due_at_epoch_millis,reminder_at_epoch_millis,completed_at_epoch_millis,created_at_epoch_millis,updated_at_epoch_millis)
                    VALUES ('todo','Done','COMPLETED','HIGH',1,NULL,NULL,2,1,2)""")
                if (version >= 4) execSQL("""INSERT INTO agent_schedules
                    (id,name,prompt,type,run_at_epoch_millis,local_time,days_of_week,interval_minutes,timezone,enabled,next_run_at_epoch_millis,last_result,created_at_epoch_millis,updated_at_epoch_millis)
                    VALUES ('plan','Keep','Read','EVERY',NULL,NULL,'',15,'UTC',1,NULL,NULL,1,1)""")
                close()
            }
            val database = Room.databaseBuilder(context, MochiDatabase::class.java, DATABASE_NAME)
                .addMigrations(MochiDatabase.MIGRATION_1_2, MochiDatabase.MIGRATION_2_3,
                    MochiDatabase.MIGRATION_3_4, MochiDatabase.MIGRATION_4_5, MochiDatabase.MIGRATION_5_6, MochiDatabase.MIGRATION_6_7, MochiDatabase.MIGRATION_7_8, MochiDatabase.MIGRATION_8_9)
                .allowMainThreadQueries().build()
            try {
                val db = database.openHelper.writableDatabase
                db.query("SELECT COUNT(*) FROM daily_journals").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM journal_settings WHERE enabled = 1").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                db.query("SELECT recurrence_rule, revision, series_id FROM calendar_events WHERE id='event'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("FREQ=DAILY", it.getString(0))
                    assertEquals(0, it.getLong(1))
                    assertTrue(it.isNull(2))
                }
                db.query("SELECT status, priority, completed_at_epoch_millis FROM todos WHERE id='todo'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("COMPLETED", it.getString(0))
                    assertEquals("HIGH", it.getString(1))
                    assertEquals(2, it.getLong(2))
                }
                if (version >= 4) db.query("SELECT ends_at_epoch_millis, enabled, last_result FROM agent_schedules").use {
                    assertTrue(it.moveToFirst())
                    assertTrue(it.isNull(0))
                    assertEquals(1, it.getInt(1))
                    assertEquals(if (version < 6) "INTERRUPTED" else null, it.getString(2))
                }
            } finally { database.close() }
        }
    }

    @Test
    fun `journal migration preserves existing content without inventing source counts`() {
        createDatabaseFromSchema(7).apply {
            execSQL("INSERT INTO daily_journals (date,timezone,status,title,text,error,updatedAt,claim) VALUES ('2026-11-08','Asia/Shanghai','READY','Decision','Saved paragraph',NULL,123,NULL)")
            close()
        }
        val database = Room.databaseBuilder(context, MochiDatabase::class.java, DATABASE_NAME)
            .addMigrations(MochiDatabase.MIGRATION_7_8, MochiDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.query("SELECT title,text,conversationCount,userScheduleCount,taskCount,generatedAt FROM daily_journals").use {
                assertTrue(it.moveToFirst())
                assertEquals("Decision", it.getString(0))
                assertEquals("Saved paragraph", it.getString(1))
                for (column in 2..5) assertTrue(it.isNull(column))
            }
        } finally { database.close() }
    }

    @Test
    fun `recall migration indexes retained journal content without enabling settings`() {
        createDatabaseFromSchema(8).apply {
            execSQL("INSERT INTO journal_settings (id,enabled,since) VALUES (0,0,'2026-11-01')")
            execSQL("INSERT INTO daily_journals (date,timezone,status,title,text,error,updatedAt,claim) VALUES ('2026-11-08','Asia/Shanghai','FAILED','旅行','决定去杭州',NULL,123,NULL)")
            execSQL("INSERT INTO daily_journals (date,timezone,status,title,text,error,updatedAt,claim) VALUES ('2026-11-07','UTC','DELETED','','',NULL,124,NULL)")
            close()
        }
        val database = Room.databaseBuilder(context, MochiDatabase::class.java, DATABASE_NAME)
            .addMigrations(MochiDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val db = database.openHelper.writableDatabase
            db.query("""SELECT date FROM journal_search WHERE search_text MATCH '"杭州"'""").use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-08", it.getString(0))
                assertTrue(!it.moveToNext())
            }
            db.query("SELECT enabled,since,revision FROM journal_settings").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("2026-11-01", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
        } finally { database.close() }
    }

    private fun createDatabaseFromSchema(version: Int): SQLiteDatabase {
        databaseFile.parentFile?.mkdirs()
        val schemaPath =
            "com.example.mochi_pet.core.database.MochiDatabase/$version.json"
        val schema = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(schemaPath),
        ) {
            "Missing Room schema resource: $schemaPath"
        }.use { input ->
            Json.parseToJsonElement(input.bufferedReader().readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        }
        return SQLiteDatabase.openOrCreateDatabase(databaseFile, null).apply {
            schema.getValue("entities")
                .jsonArray
                .forEach { entityElement ->
                    val entity = entityElement.jsonObject
                    val tableName = entity.getValue("tableName")
                        .jsonPrimitive
                        .content
                    execSQL(
                        entity.getValue("createSql")
                            .jsonPrimitive
                            .content
                            .replace("\${TABLE_NAME}", tableName),
                    )
                    entity["indices"]
                        ?.jsonArray
                        ?.forEach { indexElement ->
                            execSQL(
                                indexElement.jsonObject
                                    .getValue("createSql")
                                    .jsonPrimitive
                                    .content
                                    .replace("\${TABLE_NAME}", tableName),
                            )
                        }
                }
            schema.getValue("setupQueries")
                .jsonArray
                .forEach { query ->
                    execSQL(query.jsonPrimitive.content)
                }
            setVersion(version)
        }
    }

    private companion object {
        const val DATABASE_NAME = "memory-migration-test"
    }
}
