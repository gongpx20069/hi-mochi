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
@Config(sdk = [35])
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
            .addMigrations(MochiDatabase.MIGRATION_4_5)
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
        } finally {
            database.close()
        }
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
