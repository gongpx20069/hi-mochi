package com.example.mochi_pet.core.persona

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
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
class PersonaRepositoryTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.filesDir, "persona")
        directory.deleteRecursively()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `persona seeds three files and updates one atomically`() = runBlocking {
        val repository = FilePersonaRepository(context)

        val initial = repository.load()
        assertTrue(initial.soul.contains("practical"))
        assertTrue(initial.soul.contains("## Purpose"))
        assertTrue(initial.user.contains("## Preferences"))
        assertTrue(initial.agents.contains("# Agent Rules"))
        assertTrue(File(directory, "SOUL.md").isFile)
        assertTrue(File(directory, "USER.md").isFile)
        assertTrue(File(directory, "AGENTS.md").isFile)
        assertEquals(false, File(directory, "HEARTBEAT.md").exists())

        val updated = repository.update(
            PersonaDocument.USER,
            "# User\n\nPrefers Kotlin.",
        )

        assertTrue(updated.user.contains("Prefers Kotlin"))
        assertEquals(initial.soul, updated.soul)
    }
}
