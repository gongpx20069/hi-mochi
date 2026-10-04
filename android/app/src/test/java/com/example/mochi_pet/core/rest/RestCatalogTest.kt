package com.example.mochi_pet.core.rest

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import com.example.mochi_pet.core.tools.DataStoreToolCatalogRepository
import java.io.File
import java.time.LocalDate
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestCatalogTest {
    private val directory = createTempDirectory("mochi-rest-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "tools.preferences_pb") }
    private val cipher = object : ApiKeyCipher {
        override fun encrypt(plaintext: String) = EncryptedSecret(Base64.getEncoder().encodeToString(plaintext.toByteArray()), "fixture")
        override fun decrypt(secret: EncryptedSecret) = String(Base64.getDecoder().decode(secret.ciphertext))
    }
    private val seenSecrets = mutableListOf<String>()
    private var response = json("""{"value":22,"echo":"fixture-key"}""")
    private var onCall: suspend () -> Unit = {}
    private val transport = RestTransport { _, _, _, secret ->
        seenSecrets += secret
        onCall()
        RestResponse(200, response)
    }
    private fun repository() = DataStoreToolCatalogRepository(store, cipher, McpStreamableHttpClient(), restTransport = transport)
    private val repo = repository()
    private val connection = RestConnection(name = "Fixture", baseUrl = "https://api.example.com")
    private val tool = RestToolDefinition(name = "Read", description = "Read fixture value",
        outputs = listOf(RestOutputField("/value", "value", type = RestValueType.NUMBER)), requiresConfirmation = false)
    private val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
    private val catalogKey = stringPreferencesKey("tools.catalog")

    @After fun cleanUp() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test fun `save encrypts credentials and preserves unrelated settings across reloads`() = runBlocking {
        repo.setBuiltInEnabled("manage_mochi_todo", false)
        val saved = saveConnection()
        assertFalse(saved.enabled)
        assertTrue(seenSecrets.isEmpty())
        assertFalse(store.data.first()[catalogKey]!!.contains("fixture-key"))
        assertFalse(repository().isBuiltInEnabled("manage_mochi_todo"))
        val edited = repo.changeRestApi(RestApiChange.SaveConnection(RestConnectionInput(saved.copy(name = "Renamed"))))
            .restConnections.single().connection
        repo.testRestApi(RestConnectionInput(edited), tool, json("{}"))
        assertEquals(listOf("fixture-key"), seenSecrets)
        assertTrue(repository().loadSummary().restConnections.single().hasSecret)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.changeRestApi(RestApiChange.SaveConnection(RestConnectionInput(edited.copy(baseUrl = "https://other.example.com")))) }
        }
        assertEquals("https://api.example.com/", repo.loadSummary().restConnections.single().connection.baseUrl)
    }

    @Test fun `tests expose only sanitized fields and reject changed definitions`() = runBlocking {
        val saved = saveConnection()
        val test = repo.testRestApi(RestConnectionInput(saved), tool, json("{}"))
        assertFalse(test.fields.toString().contains("fixture-key"))
        assertEquals(json("""{"value":22}"""), test.output)
        repo.changeRestApi(RestApiChange.SetEnabled(saved.id, null, true))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.testRestApi(RestConnectionInput(saved), tool, json("{}")) }
        }
        assertEquals(1, seenSecrets.size)
    }

    @Test fun `connection and individual switches revoke old handles and multiple endpoints survive`() = runBlocking {
        val saved = saveConnection()
        val withTool = repo.changeRestApi(RestApiChange.SaveTool(saved.id, saved.revision, tool.copy(enabled = true)))
            .restConnections.single().connection
        val second = tool.copy(id = RestToolDefinition().id, name = "Other", path = "/other", enabled = true)
        repo.changeRestApi(RestApiChange.SaveTool(saved.id, withTool.revision, second))
        assertTrue(repo.loadEnabledRestTools().isEmpty())
        repo.changeRestApi(RestApiChange.SetEnabled(saved.id, null, true))
        assertEquals(2, repository().loadEnabledRestTools().size)
        val handle = repo.loadEnabledRestTools().first { it.name == restToolAlias(saved.id, tool.id) }
        assertEquals("ok", handle.execute(json("{}"), context).status)
        repo.changeRestApi(RestApiChange.SetEnabled(saved.id, tool.id, false))
        assertEquals("PERMISSION_DENIED", handle.execute(json("{}"), context).code)
        assertEquals(1, seenSecrets.size)
        assertEquals(1, repo.loadEnabledRestTools().size)
        repo.changeRestApi(RestApiChange.Delete(saved.id, second.id))
        assertTrue(repo.loadEnabledRestTools().isEmpty())
    }

    @Test fun `in flight revocation discards results and deleted editors cannot resurrect connections`() = runBlocking {
        val saved = saveConnection()
        repo.changeRestApi(RestApiChange.SaveTool(saved.id, saved.revision, tool.copy(enabled = true)))
        repo.changeRestApi(RestApiChange.SetEnabled(saved.id, null, true))
        val handle = repo.loadEnabledRestTools().single()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        onCall = { started.complete(Unit); finish.await() }
        val result = async { handle.execute(json("{}"), context) }
        started.await()
        repo.changeRestApi(RestApiChange.Delete(saved.id))
        finish.complete(Unit)
        assertEquals("PERMISSION_DENIED", result.await().code)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.changeRestApi(RestApiChange.SaveConnection(RestConnectionInput(saved, "fixture-key"))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.testRestApi(RestConnectionInput(saved, "fixture-key"), tool, json("{}")) }
        }
        assertTrue(repo.loadSummary().restConnections.isEmpty())
    }

    @Test fun `credentials in definitions and stale tool edits are rejected without overwriting`() = runBlocking {
        val saved = saveConnection()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.changeRestApi(RestApiChange.SaveTool(saved.id, saved.revision, tool.copy(description = "fixture-key"))) }
        }
        repo.changeRestApi(RestApiChange.SaveTool(saved.id, saved.revision, tool))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.changeRestApi(RestApiChange.SaveTool(saved.id, saved.revision, tool.copy(name = "Stale"))) }
        }
        assertEquals("Read", repo.loadSummary().restConnections.single().connection.tools.single().name)
    }

    @Test fun `corrupt stored catalog cannot be silently reset by updates`() = runBlocking {
        store.edit { it[catalogKey] = "{broken" }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.setBuiltInEnabled("manage_mochi_todo", false) } }
        assertEquals("{broken", store.data.first()[catalogKey])
    }

    private suspend fun saveConnection() = repo.changeRestApi(RestApiChange.SaveConnection(RestConnectionInput(connection, "fixture-key")))
        .restConnections.single().connection
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
