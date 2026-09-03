package com.example.mochi_mijia

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MiotCrypto {
    fun nonce(random: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(
            ByteArray(12).also(random::nextBytes),
        )

    fun signedNonce(
        ssecurity: String,
        nonce: String,
    ): String {
        val securityBytes = Base64.getDecoder().decode(ssecurity)
        val nonceBytes = Base64.getDecoder().decode(nonce)
        return Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(securityBytes + nonceBytes),
        )
    }

    fun signature(
        path: String,
        signedNonce: String,
        nonce: String,
        data: String,
    ): String {
        require(path.startsWith("/") && !path.startsWith("/app/")) {
            "MIoT signing path must start at the application endpoint."
        }
        val message = "$path&$signedNonce&$nonce&data=$data"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                Base64.getDecoder().decode(signedNonce),
                "HmacSHA256",
            ),
        )
        return Base64.getEncoder().encodeToString(
            mac.doFinal(message.toByteArray(Charsets.UTF_8)),
        )
    }
}
