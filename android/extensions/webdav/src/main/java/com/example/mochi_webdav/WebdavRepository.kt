package com.example.mochi_webdav

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WebdavApplication : Application() {
    internal val repository by lazy {
        WebdavRepository(this, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}

@Serializable
internal data class WebdavTransfer(
    val id: String,
    val name: String,
    val bytes: Long,
    val direction: String,
    val createdAt: Long,
)

internal data class WebdavStoredState(
    val config: WebdavConfig? = null,
    val transfers: List<WebdavTransfer> = emptyList(),
    val loading: Boolean = true,
    val error: WebdavError? = null,
)

internal interface WebdavCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

internal class WebdavRepository(
    private val context: Context,
    scope: CoroutineScope,
    private val cipher: WebdavCipher = AndroidWebdavCipher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        File(context.noBackupFilesDir, "webdav.preferences_pb")
    }
    private val mutableState = MutableStateFlow(WebdavStoredState())
    val state = mutableState.asStateFlow()
    private val transferMutex = Mutex()
    private val directory = File(context.cacheDir, "webdav-transfers")

    init {
        scope.launch {
            try {
                dataStore.data.collect { preferences ->
                    try {
                        mutableState.value = WebdavStoredState(
                            config = preferences[CONFIG]?.let(::decodeConfig),
                            transfers = preferences[TRANSFERS]?.let { Json.decodeFromString<List<WebdavTransfer>>(it) }
                                .orEmpty().filter { validTransfer(it) },
                            loading = false,
                        )
                    } catch (_: WebdavException) {
                        mutableState.value = WebdavStoredState(loading = false, error = WebdavError.STORAGE)
                        Log.w("MochiWebdav", "Encrypted configuration is unavailable.")
                    } catch (_: SerializationException) {
                        mutableState.value = WebdavStoredState(loading = false, error = WebdavError.STORAGE)
                        Log.w("MochiWebdav", "Transfer metadata is invalid.")
                    }
                }
            } catch (_: IOException) {
                mutableState.value = WebdavStoredState(loading = false, error = WebdavError.STORAGE)
                Log.w("MochiWebdav", "Configuration storage could not be read.")
            }
        }
    }

    fun lanPermissionGranted(): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    fun ready(): Boolean = state.value.let {
        !it.loading && it.error == null && it.config != null && (!it.config.allowLan || lanPermissionGranted())
    }

    suspend fun config(): WebdavConfig {
        val config = dataStore.data.first()[CONFIG]?.let(::decodeConfig)
            ?: throw WebdavException(WebdavError.PERMISSION_DENIED)
        requireWebdav(!config.allowLan || lanPermissionGranted(), WebdavError.PERMISSION_DENIED)
        return config
    }

    suspend fun assertCurrent(revision: String) {
        requireWebdav(config().revision == revision, WebdavError.PERMISSION_DENIED)
    }

    suspend fun save(config: WebdavConfig, previousRevision: String?) = transferMutex.withLock {
        WebdavLocation(config)
        requireWebdav(!config.allowLan || lanPermissionGranted(), WebdavError.PERMISSION_DENIED)
        val encoded = encodeConfig(config)
        dataStore.edit {
            requireWebdav(it[CONFIG]?.let(::decodeConfig)?.revision == previousRevision, WebdavError.CONFLICT)
            it[CONFIG] = encoded
            if (config.revision != previousRevision) it.remove(TRANSFERS)
        }
        if (config.revision != previousRevision) {
            deleteTransferFiles()
        }
    }

    suspend fun disconnect() {
        dataStore.edit { it.remove(CONFIG) }
        clearTransfers()
    }

    suspend fun transfers(): List<WebdavTransfer> = transferMutex.withLock { readTransfers() }

    private suspend fun readTransfers(): List<WebdavTransfer> {
        val entries = dataStore.data.first()[TRANSFERS]?.let { Json.decodeFromString<List<WebdavTransfer>>(it) }.orEmpty()
        requireWebdav(entries.size <= 4, WebdavError.STORAGE)
        return entries.filter(::validTransfer)
    }

    private fun validTransfer(entry: WebdavTransfer): Boolean {
        requireWebdav(entry.bytes in 0..MAX_TRANSFER_BYTES && entry.name.length <= 128 &&
            entry.direction in setOf("import", "download"), WebdavError.STORAGE)
        return clock() - entry.createdAt in 0..TRANSFER_TTL && file(entry.id).isFile
    }

    private fun file(id: String): File {
        requireWebdav(id.matches(Regex("[a-f0-9-]{36}")), WebdavError.INVALID_ARGUMENT)
        return File(directory, id)
    }

    suspend fun <T> withTransfer(id: String, action: suspend (WebdavTransfer, File) -> T): T =
        transferMutex.withLock {
            val entry = readTransfers().firstOrNull { it.id == id } ?: throw WebdavException(WebdavError.NOT_FOUND)
            action(entry, file(entry.id))
        }

    suspend fun createTransfer(
        name: String,
        direction: String,
        revision: String,
        write: suspend (File, Long) -> Unit,
    ): WebdavTransfer = transferMutex.withLock {
        assertCurrent(revision)
        requireWebdav(direction in setOf("import", "download"))
        val entries = readTransfers()
        requireWebdav(entries.size < 4, WebdavError.LIMIT_EXCEEDED)
        val maximum = minOf(MAX_TRANSFER_BYTES, 64L * 1_024 * 1_024 - entries.sumOf { it.bytes })
        requireWebdav(maximum > 0, WebdavError.LIMIT_EXCEEDED)
        requireWebdav(directory.isDirectory || directory.mkdirs(), WebdavError.STORAGE)
        // This directory contains only opaque-ID transfer files owned by this extension.
        directory.listFiles()?.filter { candidate -> entries.none { it.id == candidate.name } }?.forEach {
            if (!it.delete()) Log.w("MochiWebdav", "Expired transfer cleanup failed.")
        }
        val id = UUID.randomUUID().toString()
        val target = file(id)
        var committed = false
        try {
            write(target, maximum)
            assertCurrent(revision)
            requireWebdav(target.isFile && target.length() <= maximum, WebdavError.LIMIT_EXCEEDED)
            val entry = WebdavTransfer(id, safeFileName(name), target.length(), direction, clock())
            dataStore.edit {
                requireWebdav(it[CONFIG]?.let(::decodeConfig)?.revision == revision, WebdavError.PERMISSION_DENIED)
                it[TRANSFERS] = Json.encodeToString(entries + entry)
            }
            committed = true
            entry
        } finally {
            if (!committed && target.exists() && !target.delete()) Log.w("MochiWebdav", "Incomplete transfer cleanup failed.")
        }
    }

    suspend fun forgetTransfer(id: String) = transferMutex.withLock {
        val entries = readTransfers()
        requireWebdav(entries.any { it.id == id }, WebdavError.NOT_FOUND)
        val target = file(id)
        requireWebdav(!target.exists() || target.delete(), WebdavError.STORAGE)
        dataStore.edit { it[TRANSFERS] = Json.encodeToString(entries.filterNot { item -> item.id == id }) }
    }

    private suspend fun clearTransfers() = transferMutex.withLock {
        deleteTransferFiles()
        dataStore.edit { it.remove(TRANSFERS) }
    }

    private fun deleteTransferFiles() {
        directory.listFiles()?.forEach {
            requireWebdav(it.delete(), WebdavError.STORAGE)
        }
    }

    private fun encodeConfig(config: WebdavConfig): String = cipher.encrypt(Json.encodeToString(config))

    private fun decodeConfig(value: String): WebdavConfig {
        try {
            return Json.decodeFromString(cipher.decrypt(value))
        } catch (_: IllegalArgumentException) {
            throw WebdavException(WebdavError.STORAGE)
        }
    }

    companion object {
        private val CONFIG = stringPreferencesKey("connection")
        private val TRANSFERS = stringPreferencesKey("transfers")
        private const val TRANSFER_TTL = 60 * 60 * 1_000L
        fun safeFileName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.take(128).ifBlank { "download.bin" }
    }
}

private class AndroidWebdavCipher : WebdavCipher {
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun encrypt(value: String): String {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (_: GeneralSecurityException) {
            throw WebdavException(WebdavError.STORAGE)
        }
    }

    override fun decrypt(value: String): String {
        try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            requireWebdav(bytes.size > 28, WebdavError.STORAGE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            throw WebdavException(WebdavError.STORAGE)
        } catch (_: IllegalArgumentException) {
            throw WebdavException(WebdavError.STORAGE)
        }
    }

    companion object {
        private const val KEY_ALIAS = "mochi_webdav_connection_v1"
    }
}
