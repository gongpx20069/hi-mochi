package com.example.mochi_pet.core.agentlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLinkAuthorizationTest {
    @Test
    fun `only explicit matched native result pins an independently signed package`() {
        val policy = AgentLinkAuthorization { "application-generated-nonce" }
        val request = policy.begin("independent-package-signer")
        assertTrue(policy.isPending(request.requestId, "independent-package-signer"))
        assertEquals("independent-package-signer", policy.complete(
            request.requestId, 1, true, "independent-package-signer",
        ))
        assertNull(policy.complete(request.requestId, 1, true, "independent-package-signer"))
    }

    @Test
    fun `cancel unsolicited and process restart results do not establish trust`() {
        val policy = AgentLinkAuthorization { "nonce" }
        assertNull(policy.complete("unsolicited", 1, true, "signer"))
        policy.begin("signer")
        assertNull(policy.complete("nonce", 1, false, "signer"))
        assertFalse(policy.isPending("nonce", "signer"))
        assertNull(AgentLinkAuthorization().complete("nonce", 1, true, "signer"))
    }

    @Test
    fun `wrong nonce incompatible protocol and package replacement fail closed`() {
        listOf(
            Triple("wrong", 1, "signer"),
            Triple("nonce", 2, "signer"),
            Triple("nonce", 1, "replacement-signer"),
        ).forEach { (nonce, version, signer) ->
            val policy = AgentLinkAuthorization { "nonce" }
            policy.begin("signer")
            try {
                policy.complete(nonce, version, true, signer)
                throw AssertionError("Mismatched authorization accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(policy.isPending("nonce", "signer"))
            }
        }
    }
}
