package com.example.mochi_pet.platform.browser

import android.app.Application
import android.content.MutableContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserViewOwnershipTest {
    @Test fun `late release of the previous mount cannot detach the current page`() = runBlocking<Unit> {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val runtime = AgentBrowserRuntime(application)
        runtime.beginTurn()
        try {
            val page = runtime.ensureWebView()
            val firstContext = MutableContextWrapper(application)
            val secondContext = MutableContextWrapper(application)
            val firstHost = checkNotNull(runtime.createUiHost(firstContext))
            val secondHost = checkNotNull(runtime.createUiHost(secondContext))
            assertEquals(0, firstHost.childCount)
            runtime.releaseUiHost(firstHost)
            runtime.releaseUiHost(firstHost)
            assertSame("The previous UI must not remove the page from its new owner", secondHost, page.parent)
            assertSame(secondContext, (page.context as MutableContextWrapper).baseContext)
            assertSame(page, runtime.ensureWebView())
            runtime.releaseUiHost(secondHost)
            assertNull(page.parent)
            assertSame(application, (page.context as MutableContextWrapper).baseContext)
            val restoredHost = checkNotNull(runtime.createUiHost(firstContext))
            runtime.releaseUiHost(secondHost)
            assertSame(restoredHost, page.parent)
            assertSame(page, runtime.ensureWebView())
        } finally {
            runtime.closeTurn()
        }
    }
}
