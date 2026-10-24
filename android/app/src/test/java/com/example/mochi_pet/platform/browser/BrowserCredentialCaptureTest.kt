package com.example.mochi_pet.platform.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserCredentialCaptureTest {
    private val payload = """{"username":"fixture","password":"synthetic-password"}"""

    @Test fun `SMS payload contains only origin and phone and refuses code fields`() {
        val login = parseCredentialSubmission("""{"kind":"sms","username":"+12025550123"}""",
            "https://example.com", "https://example.com/login", true)
        assertEquals(com.example.mochi_pet.core.browser.BrowserLoginKind.SMS, login.kind)
        assertEquals("+12025550123", login.username)
        assertEquals("", login.password)
        listOf(
            """{"kind":"sms","username":"+12025550123","password":"975310"}""",
            """{"kind":"sms","username":"+12025550123","code":"975310"}""",
            """{"kind":"sms","username":"975310"}""",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                parseCredentialSubmission(it, "https://example.com", "https://example.com/login", true)
            }
        }
    }

    @Test fun `only main frame matching native origin can propose credentials`() {
        val login = parseCredentialSubmission(payload, "https://example.com", "https://example.com/login", true)
        assertEquals("https://example.com/", login.origin)
        assertFalse(login.toString().contains("synthetic-password"))
        listOf("https://other.example", "https://sub.example.com", "https://example.com:444", "http://example.com").forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                parseCredentialSubmission(payload, source, "https://example.com/login", true)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCredentialSubmission(payload, "https://example.com", "https://example.com/login", false)
        }
    }

    @Test fun `malformed and oversized password messages are rejected`() {
        listOf("""{"username":3,"password":"fixture"}""", """{"username":"fixture","password":""}""", "x".repeat(32_769)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                parseCredentialSubmission(it, "https://example.com", "https://example.com", true)
            }
        }
    }

    @Test fun `captured passwords remain redacted after dismiss until the turn is reset`() {
        val capture = BrowserCredentialCapture { true }
        capture.protect("synthetic-password")
        capture.protect("synthetic-password-longer")
        capture.clear()
        assertEquals("echo [redacted]", capture.redact("echo synthetic-password"))
        assertEquals("echo [redacted]", capture.redact("echo synthetic-password-longer"))
        capture.reset()
        assertEquals("synthetic-password", capture.redact("synthetic-password"))
    }

    @Test fun `redaction capacity overflow blocks snapshots rather than forgetting secrets`() {
        val capture = BrowserCredentialCapture { true }
        repeat(33) { capture.protect("synthetic-password-$it") }
        assertThrows(IllegalStateException::class.java) { capture.redact("page text") }
        capture.reset()
        assertEquals("page text", capture.redact("page text"))
    }
}
