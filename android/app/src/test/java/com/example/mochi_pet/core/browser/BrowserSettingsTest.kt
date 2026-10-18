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
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowserSettingsTest {
    @Before fun initializePublicSuffixAssets() {
        okhttp3.OkHttp.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test fun `website groups include data-only and account-only sites without merging credential origins`() {
        val credentials = listOf(
            BrowserCredentialSummary("password", "v1", "https://login.example.co.uk:8443/", "user"),
            BrowserCredentialSummary("sms", "v1", "https://www.example.co.uk/", "+12025550123", BrowserLoginKind.SMS),
            BrowserCredentialSummary("saved", "v1", "https://saved.example/", "user"),
        )
        val groups = groupBrowserWebsites(
            listOf(BrowserSite("www.example.co.uk", 20), BrowserSite("news.example", 10), BrowserSite("empty.example", 30)),
            credentials, setOf("example.co.uk", "news.example"),
        )
        assertEquals(listOf("example.co.uk", "news.example", "saved.example"), groups.map { it.domain })
        assertEquals(credentials.take(2), groups.first().credentials)
        assertEquals("https://www.example.co.uk/", groups.first().openUrl)
        assertTrue(groups[1].hasSiteData && groups[1].credentials.isEmpty())
        assertFalse(groups[2].hasSiteData)
        assertEquals(credentials.last().origin, groups[2].openUrl)
    }

    @Test fun `website grouping respects private public suffixes and unrelated lookalike domains`() {
        val hosts = listOf("www.alice.github.io", "bob.github.io", "notalice.github.io")
        val groups = groupBrowserWebsites(hosts.map { BrowserSite(it) }, emptyList(), hosts.map(::browserWebsiteDomain).toSet())
        assertEquals(listOf("alice.github.io", "bob.github.io", "notalice.github.io"), groups.map { it.domain })
    }

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
