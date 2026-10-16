package com.example.mochi_pet.core.browser

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowserSettingsTest {
    @Test fun `agent selection wins and auto remembers last mode`() = runBlocking {
        val store = MemoryStore()
        val settings = BrowserSettings(store)
        assertEquals(BrowserMode.MOBILE, settings.mode("example.com", BrowserMode.AUTO))
        settings.visited("example.com", BrowserMode.DESKTOP)
        assertEquals(BrowserMode.DESKTOP, settings.mode("example.com", BrowserMode.AUTO))
        assertEquals(BrowserMode.MOBILE, settings.mode("example.com", BrowserMode.MOBILE))
        assertEquals(BrowserMode.MOBILE, BrowserSettings(store).mode("example.com", BrowserMode.MOBILE))
    }

    @Test fun `old manual mode preferences cannot override the agent`() = runBlocking {
        val store = MemoryStore()
        val key = stringPreferencesKey("browser.sites.v1")
        store.data.value = preferencesOf(key to """[{"host":"example.com","preference":"DESKTOP","lastMode":"MOBILE"}]""")
        val settings = BrowserSettings(store)
        assertEquals(BrowserMode.MOBILE, settings.mode("example.com", BrowserMode.AUTO))
        assertEquals(BrowserMode.MOBILE, settings.mode("example.com", BrowserMode.MOBILE))
        assertEquals(BrowserMode.DESKTOP, settings.mode("example.com", BrowserMode.DESKTOP))
        settings.visited("example.com", BrowserMode.DESKTOP)
        assertFalse(store.data.value[key]!!.contains("preference"))
    }

    @Test fun `deletion removes registrable site and subdomains but not similarly named sites`() = runBlocking {
        val settings = BrowserSettings(MemoryStore())
        listOf("example.com", "www.example.com", "notexample.com").forEach { settings.visited(it, BrowserMode.MOBILE) }
        settings.forget("example.com")
        assertEquals(listOf("notexample.com"), settings.sites.first().map { it.host })
        settings.forget(null)
        assertTrue(settings.sites.first().isEmpty())
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
