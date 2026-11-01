package com.example.mochi_pet.platform.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.browser.browserWebsiteDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSiteDataDiagnosticTest {
    @Test fun clearExplicitlyAuthorizedSiteData() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mochiAuthorizeBrowserSiteDeletion") == "true")
        val site = requireNotNull(arguments.getString("mochiClearBrowserSite"))
        val url = requireNotNull("https://$site/".toHttpUrlOrNull())
        require(site == url.host && url.topPrivateDomain() == site) {
            "Authorize one exact registrable domain, not a URL, subdomain or public suffix."
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        val runtime = app.agentBrowserRuntime
        val accounts = app.browserCredentialVault.summaries.first()
        val hosts = (accounts.mapNotNull { it.origin.toHttpUrlOrNull()?.host } +
            listOf(site, "www.$site", "passport.$site"))
            .filter { browserWebsiteDomain(it) == site }.toSet()
        runtime.clearData(site)
        assertTrue("Authorized website data remains", runtime.websiteDataDomains(hosts).isEmpty())
        assertTrue("Saved accounts must be preserved", accounts == app.browserCredentialVault.summaries.first())
    }
}
