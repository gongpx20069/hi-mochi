package com.example.mochi_webdav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WebdavRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: WebdavRepository
    private lateinit var context: Context
    private var time = 100_000L
    private val config = WebdavConfig("https://files.example/dav/", "notes", "fixture", "fixture", revision = "one")
    private val cipher = object : WebdavCipher {
        private val values = mutableMapOf<String, String>()
        override fun encrypt(value: String): String = java.util.UUID.randomUUID().toString().also { values[it] = value }
        override fun decrypt(value: String): String = values[value] ?: throw WebdavException(WebdavError.STORAGE)
    }

    @Before fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = WebdavRepository(context, scope, cipher) { time }
        repository.state.first { !it.loading }
        repository.save(config, null)
    }
    @After fun close() { scope.cancel() }

    @Test fun `staging preserves exact bytes and expiry makes files inaccessible`() = runBlocking {
        val bytes = ByteArray(256) { it.toByte() }
        val transfer = repository.createTransfer("../../a.bin", "import", "one") { file, _ -> file.writeBytes(bytes) }
        assertEquals("a.bin", transfer.name)
        repository.withTransfer(transfer.id) { _, file -> assertArrayEquals(bytes, file.readBytes()) }
        assertFalse(File(context.noBackupFilesDir, "webdav.preferences_pb").readBytes().toString(Charsets.UTF_8).contains("fixture"))
        time += 3_600_001
        assertTrue(repository.transfers().isEmpty())
        fails(WebdavError.NOT_FOUND) { repository.withTransfer(transfer.id) { _, _ -> fail() } }
    }

    @Test fun `failed or cancelled imports leave no staged file`() = runBlocking {
        fails(WebdavError.LIMIT_EXCEEDED) {
            repository.createTransfer("x", "import", "one") { file, _ ->
                file.writeText("partial")
                throw WebdavException(WebdavError.LIMIT_EXCEEDED)
            }
        }
        assertTrue(repository.transfers().isEmpty())
        assertTrue(File(context.cacheDir, "webdav-transfers").listFiles().orEmpty().isEmpty())
        try {
            repository.createTransfer("x", "import", "one") { file, _ ->
                file.writeText("partial"); throw kotlinx.coroutines.CancellationException()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue(File(context.cacheDir, "webdav-transfers").listFiles().orEmpty().isEmpty())
        }
        Unit
    }

    @Test fun `connection changes discard staged capabilities and reject old requests`() = runBlocking {
        val item = repository.createTransfer("x", "import", "one") { file, _ -> file.writeText("sample") }
        repository.save(config, "one")
        assertEquals(item.id, repository.transfers().single().id)
        repository.save(config.copy(root = "other", revision = "two"), "one")
        assertTrue(repository.transfers().isEmpty())
        fails(WebdavError.PERMISSION_DENIED) { repository.assertCurrent("one") }
        fails(WebdavError.CONFLICT) { repository.save(config, "one") }
        fails(WebdavError.PERMISSION_DENIED) {
            executeWebdav(repository, "webdav_transfer", buildJsonObject {
                put("action", "list"); put("_connection_id", "one")
            })
        }
        repository.disconnect()
        fails(WebdavError.PERMISSION_DENIED) { repository.config() }
    }

    @Test fun `staged file count is bounded and forgetting does not touch remote state`() = runBlocking {
        repeat(4) { repository.createTransfer("x$it", "import", "one") { file, _ -> file.writeText("x") } }
        fails(WebdavError.LIMIT_EXCEEDED) {
            repository.createTransfer("fifth", "import", "one") { _, _ -> fail("Must reject before reading a source") }
        }
        val first = repository.transfers().first()
        repository.forgetTransfer(first.id)
        assertEquals(3, repository.transfers().size)
        fails(WebdavError.INVALID_ARGUMENT) {
            executeWebdav(repository, "webdav_transfer", buildJsonObject {
                put("action", "list"); put("path", "/unexpected"); put("_connection_id", "one")
            })
        }
    }

    private suspend fun fails(code: WebdavError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: WebdavException) { assertEquals(code, failure.code) }
    }
}
