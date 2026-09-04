package com.example.mochi_mijia

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MijiaSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: MijiaSecretCipher = AndroidMijiaSecretCipher(),
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    suspend fun load(): MijiaSession? {
        val preferences = dataStore.data.first()
        val ciphertext = preferences[SESSION_CIPHERTEXT] ?: return null
        val iv = preferences[SESSION_IV] ?: return null
        return runCatching {
            json.decodeFromString<MijiaSession>(
                cipher.decrypt(ciphertext, iv),
            )
        }.getOrElse { error ->
            throw MijiaProviderException(
                "Stored Mi Home authorization is invalid.",
                error,
            )
        }
    }

    suspend fun save(session: MijiaSession) {
        val encrypted = cipher.encrypt(json.encodeToString(session))
        dataStore.edit { preferences ->
            preferences[SESSION_CIPHERTEXT] = encrypted.ciphertext
            preferences[SESSION_IV] = encrypted.iv
            preferences[DEVICE_ID] = session.deviceId
            preferences.remove(AUTHORIZATION_EXPIRED)
        }
    }

    suspend fun update(transform: (MijiaSession) -> MijiaSession): MijiaSession {
        val current = load()
            ?: throw MijiaAuthorizationException("Connect Mi Home first.")
        return transform(current).also { save(it) }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(SESSION_CIPHERTEXT)
            preferences.remove(SESSION_IV)
            preferences.remove(AUTHORIZATION_EXPIRED)
        }
    }

    suspend fun isAuthorizationExpired(): Boolean =
        dataStore.data.first()[AUTHORIZATION_EXPIRED] == true

    suspend fun markAuthorizationExpired() {
        dataStore.edit { preferences ->
            preferences[AUTHORIZATION_EXPIRED] = true
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        val existing = dataStore.data.first()[DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        val generated = bytes.joinToString("") { "%02X".format(it) }
        dataStore.edit { preferences ->
            if (preferences[DEVICE_ID].isNullOrBlank()) {
                preferences[DEVICE_ID] = generated
            }
        }
        return dataStore.data.first()[DEVICE_ID] ?: generated
    }

    private companion object {
        val SESSION_CIPHERTEXT = stringPreferencesKey("session.ciphertext")
        val SESSION_IV = stringPreferencesKey("session.iv")
        val DEVICE_ID = stringPreferencesKey("device.id")
        val AUTHORIZATION_EXPIRED =
            booleanPreferencesKey("session.authorization_expired")
    }
}

data class MijiaEncryptedSecret(
    val ciphertext: String,
    val iv: String,
)

interface MijiaSecretCipher {
    fun encrypt(plaintext: String): MijiaEncryptedSecret

    fun decrypt(
        ciphertext: String,
        iv: String,
    ): String
}

class AndroidMijiaSecretCipher(
    private val alias: String = "mochi_mijia_session_v1",
) : MijiaSecretCipher {
    override fun encrypt(plaintext: String): MijiaEncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return MijiaEncryptedSecret(
            ciphertext = Base64.encodeToString(
                cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            ),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    override fun decrypt(
        ciphertext: String,
        iv: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(
                GCM_TAG_BITS,
                Base64.decode(iv, Base64.NO_WRAP),
            ),
        )
        return cipher.doFinal(
            Base64.decode(ciphertext, Base64.NO_WRAP),
        ).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

fun createMijiaDataStore(context: Context): DataStore<Preferences> =
    androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("mijia_session.preferences_pb")
    }
