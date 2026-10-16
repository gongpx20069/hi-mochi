package com.example.mochi_pet.core.browser

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
enum class BrowserMode { AUTO, MOBILE, DESKTOP }

@Serializable
data class BrowserSite(
    val host: String,
    val lastVisited: Long = 0,
    val lastMode: BrowserMode = BrowserMode.MOBILE,
)

class BrowserSettings(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("browser.sites.v1")
    private val json = Json { ignoreUnknownKeys = true }
    val sites = store.data.map { decode(it[key]) }

    private fun decode(raw: String?): List<BrowserSite> =
        raw?.let { json.decodeFromString<List<BrowserSite>>(it) } ?: emptyList()

    suspend fun mode(host: String, requested: BrowserMode): BrowserMode {
        val saved = sites.first().firstOrNull { it.host == host }
        return resolveBrowserMode(saved, requested)
    }

    suspend fun visited(host: String, mode: BrowserMode) {
        update(host) { it.copy(lastVisited = System.currentTimeMillis(), lastMode = mode) }
    }

    private suspend fun update(host: String, transform: (BrowserSite) -> BrowserSite) {
        require("https://$host".toHttpUrl().host == host) { "Invalid website host." }
        store.edit { prefs ->
            val current = decode(prefs[key])
            val site = transform(current.firstOrNull { it.host == host } ?: BrowserSite(host))
            prefs[key] = json.encodeToString((current.filterNot { it.host == host } + site).sortedByDescending { it.lastVisited }.take(100))
        }
    }

    suspend fun forget(site: String?) {
        store.edit { prefs ->
            prefs[key] = json.encodeToString(if (site == null) emptyList() else decode(prefs[key]).filterNot {
                it.host == site || it.host.endsWith(".$site")
            })
        }
    }
}

internal fun resolveBrowserMode(site: BrowserSite?, requested: BrowserMode): BrowserMode = when {
    requested != BrowserMode.AUTO -> requested
    else -> site?.lastMode?.takeIf { it != BrowserMode.AUTO } ?: BrowserMode.MOBILE
}
