package com.example.mochi_pet.platform.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserModeTest {
    @Test fun `desktop user agent preserves installed Chrome version and engine compatibility`() {
        val ua = "Mozilla/5.0 (Linux; Android 14; Phone Build/test; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.123 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.123 Safari/537.36",
            desktopUserAgent(ua),
        )
    }

    @Test fun `snapshot URL strips user info query and fragments`() {
        assertEquals("https://example.com/path", safeBrowserUrl("https://user:synthetic@example.com/path?code=fixture#token=fixture"))
        assertEquals("", safeBrowserUrl("javascript:alert(1)"))
    }
}
