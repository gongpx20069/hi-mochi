package com.example.mochi_pet.core.browser

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import com.example.mochi_pet.core.settings.ProviderSecretException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class BrowserLoginKind { PASSWORD, SMS }

data class BrowserCredentialSummary(
    val id: String,
    val revision: String,
    val origin: String,
    val username: String,
    val kind: BrowserLoginKind = BrowserLoginKind.PASSWORD,
)

@Serializable
class BrowserLogin(
    val origin: String,
    val username: String,
    val password: String = "",
    val kind: BrowserLoginKind = BrowserLoginKind.PASSWORD,
) {
    override fun toString() = "BrowserLogin([redacted])"
}

@Serializable
private data class StoredBrowserCredential(val id: String, val revision: String, val login: BrowserLogin)

class BrowserVaultException(message: String) : IllegalStateException(message)

fun isBrowserPhoneNumber(value: String): Boolean =
    value.matches(Regex("\\+?[0-9 ()-]{6,40}")) && value.count(Char::isDigit) in 7..20

fun browserOrigin(url: String): String {
    val parsed = url.toHttpUrl()
    require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Use a secure website without embedded credentials." }
    return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
}

fun browserDestination(input: String): String {
    val text = input.trim()
    require(text.isNotEmpty() && text.length <= 2_000) { "Enter a search or website address (up to 2000 characters)." }
    return when {
        text.startsWith("https://", true) || text.startsWith("http://", true) -> text
        !text.any(Char::isWhitespace) && "." in text && "://" !in text -> "https://$text"
        else -> "https://www.bing.com/search".toHttpUrl().newBuilder().addQueryParameter("q", text).build().toString()
    }
}

class BrowserCredentialVault(private val store: DataStore<Preferences>, private val cipher: ApiKeyCipher) {
    private val key = stringPreferencesKey("browser.credentials.v1")
    private val json = Json { ignoreUnknownKeys = true }
    val summaries = store.data.map { prefs ->
        read(prefs).map { BrowserCredentialSummary(it.id, it.revision, it.login.origin, it.login.username, it.login.kind) }
    }

    suspend fun save(login: BrowserLogin) {
        validate(login)
        change { entries ->
            val existing = entries.firstOrNull {
                it.login.origin == login.origin && it.login.username == login.username && it.login.kind == login.kind
            }
            check(existing != null || entries.size < 100) { "Credential storage is full. Delete an unused account first." }
            val record = StoredBrowserCredential(existing?.id ?: UUID.randomUUID().toString(), UUID.randomUUID().toString(), login)
            listOf(record) + entries.filterNot { it.id == record.id }
        }
    }

    suspend fun edit(summary: BrowserCredentialSummary, origin: String, username: String, replacementPassword: String) {
        change { entries ->
            val current = current(entries, summary)
            val normalized = browserOrigin(origin)
            require(current.login.kind == BrowserLoginKind.SMS || normalized == current.login.origin || replacementPassword.isNotEmpty()) {
                "Enter the password again when changing the website."
            }
            val login = BrowserLogin(normalized, username.trim(), replacementPassword.ifEmpty { current.login.password }, current.login.kind)
            validate(login)
            require(entries.none {
                it.id != summary.id && it.login.origin == login.origin && it.login.username == login.username && it.login.kind == login.kind
            }) {
                "An account with this username already exists for this website."
            }
            entries.map {
                if (it.id == summary.id) it.copy(revision = UUID.randomUUID().toString(), login = login) else it
            }
        }
    }

    suspend fun delete(summary: BrowserCredentialSummary) {
        change { entries -> current(entries, summary); entries.filterNot { it.id == summary.id } }
    }

    suspend fun load(summary: BrowserCredentialSummary): BrowserLogin =
        current(read(store.data.first()), summary).login

    private fun current(entries: List<StoredBrowserCredential>, summary: BrowserCredentialSummary): StoredBrowserCredential =
        entries.firstOrNull { it.id == summary.id && it.revision == summary.revision }
            ?: throw IllegalStateException("This saved account changed. Reopen it before continuing.")

    private suspend fun change(transform: (List<StoredBrowserCredential>) -> List<StoredBrowserCredential>) {
        store.edit { prefs ->
            val updated = transform(read(prefs))
            try {
                prefs[key] = json.encodeToString(cipher.encrypt(json.encodeToString(updated)))
            } catch (_: ProviderSecretException) {
                throw BrowserVaultException("Could not encrypt saved website credentials.")
            }
        }
    }

    private fun read(prefs: Preferences): List<StoredBrowserCredential> {
        val raw = prefs[key] ?: return emptyList()
        try {
            val entries = json.decodeFromString<List<StoredBrowserCredential>>(
                cipher.decrypt(json.decodeFromString<EncryptedSecret>(raw)),
            )
            require(entries.size <= 100 && entries.map { it.id }.distinct().size == entries.size)
            entries.forEach { validate(it.login) }
            return entries
        } catch (_: ProviderSecretException) {
            throw BrowserVaultException("Saved website credentials could not be decrypted. Existing data was not changed.")
        } catch (_: SerializationException) {
            throw BrowserVaultException("Saved website credentials are invalid. Existing data was not changed.")
        } catch (_: IllegalArgumentException) {
            throw BrowserVaultException("Saved website credentials are invalid. Existing data was not changed.")
        }
    }

    private fun validate(login: BrowserLogin) {
        require(browserOrigin(login.origin) == login.origin && login.origin.toHttpUrlOrNull() != null) { "Invalid website origin." }
        require(login.username.isNotBlank() && login.username.length <= 320) { "Enter a username (up to 320 characters)." }
        when (login.kind) {
            BrowserLoginKind.PASSWORD -> require(login.password.isNotEmpty() && login.password.length <= 4_096) {
                "Enter a password (up to 4096 characters)."
            }
            BrowserLoginKind.SMS -> {
                require(isBrowserPhoneNumber(login.username)) { "Enter a valid phone number." }
                require(login.password.isEmpty()) { "Verification codes must never be saved." }
            }
        }
    }
}
