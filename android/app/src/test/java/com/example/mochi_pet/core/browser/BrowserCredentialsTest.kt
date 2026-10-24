package com.example.mochi_pet.core.browser

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowserCredentialsTest {
    @Test fun `SMS accounts cannot store codes or overwrite legacy password accounts`() = runBlocking {
        val store = MemoryStore()
        val cipher = TestCipher()
        val key = stringPreferencesKey("browser.credentials.v1")
        store.data.value = preferencesOf(key to kotlinx.serialization.json.Json.encodeToString(
            EncryptedSecret.serializer(), cipher.encrypt(
                """[{"id":"legacy","revision":"one","login":{"origin":"https://example.com/","username":"+12025550123","password":"fixture-password"}}]""",
            ),
        ))
        val vault = BrowserCredentialVault(store, cipher)
        assertEquals(BrowserLoginKind.PASSWORD, vault.summaries.first().single().kind)
        vault.save(BrowserLogin("https://example.com/", "+12025550123", kind = BrowserLoginKind.SMS))
        val entries = vault.summaries.first()
        assertEquals(2, entries.size)
        assertEquals("fixture-password", vault.load(entries.single { it.kind == BrowserLoginKind.PASSWORD }).password)
        val sms = entries.single { it.kind == BrowserLoginKind.SMS }
        assertEquals("", vault.load(sms).password)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { vault.save(BrowserLogin(sms.origin, sms.username, "975310", BrowserLoginKind.SMS)) }
        }
        vault.edit(sms, "https://other.example/", "+12025550124", "")
        assertEquals("", vault.load(vault.summaries.first().single { it.kind == BrowserLoginKind.SMS }).password)
    }

    @Test fun `vault encrypts all account fields and updates only matching website and username`() = runBlocking {
        val store = MemoryStore()
        val cipher = TestCipher()
        val vault = BrowserCredentialVault(store, cipher)
        vault.save(BrowserLogin("https://example.com/", "fixture-user", "fixture-password"))
        val stored = store.data.value.asMap().values.single().toString()
        listOf("fixture-user", "fixture-password", "example.com").forEach { assertFalse(stored.contains(it)) }
        vault.save(BrowserLogin("https://example.com/", "fixture-user", "replacement-password"))
        vault.save(BrowserLogin("https://other.example/", "fixture-user", "other-password"))
        val restored = BrowserCredentialVault(store, cipher)
        val entries = restored.summaries.first()
        assertEquals(2, entries.size)
        val selected = entries.single { it.origin == "https://example.com/" }
        assertEquals("replacement-password", restored.load(selected).password)
        restored.delete(selected)
        assertEquals("https://other.example/", restored.summaries.first().single().origin)
    }

    @Test fun `editing rejects stale references and requires fresh password for a different origin`() = runBlocking {
        val vault = BrowserCredentialVault(MemoryStore(), TestCipher())
        vault.save(BrowserLogin("https://example.com/", "fixture", "fixture-password"))
        val original = vault.summaries.first().single()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { vault.edit(original, "https://other.example/", "fixture", "") }
        }
        vault.edit(original, original.origin, "renamed", "")
        assertEquals("fixture-password", vault.load(vault.summaries.first().single()).password)
        assertThrows(IllegalStateException::class.java) { runBlocking { vault.delete(original) } }
        Unit
    }

    @Test fun `corruption is reported and never overwritten by a save`() = runBlocking {
        val store = MemoryStore()
        val key = stringPreferencesKey("browser.credentials.v1")
        store.data.value = preferencesOf(key to "not-json")
        val vault = BrowserCredentialVault(store, TestCipher())
        assertThrows(BrowserVaultException::class.java) {
            runBlocking { vault.save(BrowserLogin("https://example.com/", "fixture", "fixture-password")) }
        }
        assertEquals("not-json", store.data.value[key])
    }

    @Test fun `search is Bing encoded and site origins are exact HTTPS scheme host and port`() {
        assertEquals("https://www.bing.com/search?q=hello%20%26%20world", browserDestination("hello & world"))
        assertEquals("https://example.com/path", browserDestination("example.com/path"))
        assertEquals("https://example.com:8443/", browserOrigin("https://example.com:8443/login?token=fixture"))
        assertThrows(IllegalArgumentException::class.java) { browserOrigin("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { browserOrigin("https://user:fixture@example.com") }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class TestCipher : ApiKeyCipher {
        private val key = KeyGenerator.getInstance("AES").generateKey()
        override fun encrypt(plaintext: String): EncryptedSecret {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return EncryptedSecret(Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray())),
                Base64.getEncoder().encodeToString(cipher.iv))
        }
        override fun decrypt(secret: EncryptedSecret): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(secret.iv)))
            }
            return String(cipher.doFinal(Base64.getDecoder().decode(secret.ciphertext)))
        }
    }
}
