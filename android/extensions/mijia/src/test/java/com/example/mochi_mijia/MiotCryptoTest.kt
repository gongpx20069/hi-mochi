package com.example.mochi_mijia

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MiotCryptoTest {
    @Test
    fun publishedSignatureVectorMatches() {
        val ssecurity = "MDEyMzQ1Njc4OWFiY2RlZg=="
        val nonce = "aB3xY9zQ1mN7pL5k"
        val data =
            """{"params":[{"did":"123456789","siid":2,"piid":1}]}"""

        val signedNonce = MiotCrypto.signedNonce(ssecurity, nonce)
        val signature = MiotCrypto.signature(
            path = "/miotspec/prop/get",
            signedNonce = signedNonce,
            nonce = nonce,
            data = data,
        )

        assertEquals(
            "pPNJ3i4wXoCA/ByAFzOu56H70wkMoG9UaZgOHmqUWBk=",
            signedNonce,
        )
        assertEquals(
            "kgI5fPxjNUgyITsM42VfDHGFhft76o1ZbVNayp26OCI=",
            signature,
        )
    }

    @Test
    fun generatedNonceContainsTwelveBytes() {
        val first = ByteArray(12) { it.toByte() }
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                first.copyInto(bytes)
            }
        }

        assertArrayEquals(
            first,
            Base64.getDecoder().decode(MiotCrypto.nonce(random)),
        )
    }
}
