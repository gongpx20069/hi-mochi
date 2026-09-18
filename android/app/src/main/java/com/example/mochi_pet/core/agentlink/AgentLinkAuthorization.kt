package com.example.mochi_pet.core.agentlink

import java.util.UUID

/** A returned nonce is a one-use correlation value, not a bearer credential. */
class AgentLinkAuthorization(
    private val newNonce: () -> String = { UUID.randomUUID().toString() },
) {
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun begin(signer: String): AgentLinkActivityRequest {
        require(signer.isNotBlank())
        val request = AgentLinkActivityRequest(newNonce())
        pending = request.requestId to signer
        return request
    }

    @Synchronized
    fun isPending(requestId: String, signer: String): Boolean =
        pending == (requestId to signer)

    @Synchronized
    fun complete(
        requestId: String?,
        version: Int,
        accepted: Boolean,
        currentSigner: String?,
    ): String? {
        val expected = pending
        pending = null
        if (!accepted || expected == null) return null
        require(expected.first == requestId && version == 1 && expected.second == currentSigner) {
            "AgentLink authorization result or package identity did not match this request"
        }
        return expected.second
    }
}
