package com.example.mochi_mijia

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRc4CryptoTest {
    @Test
    fun regressionVectorMatches() {
        val prepared = CameraRc4Crypto.prepare(
            method = "GET",
            path = "/miot/camera/app/v1/img",
            ssecurity = "MDEyMzQ1Njc4OWFiY2RlZg==",
            nonce = "aB3xY9zQ1mN7pL5k",
            data = """{"did":"123","fileId":"f1","stoId":"i1","segmentIv":"AAECAwQFBgcICQoLDA0ODw=="}""",
        )

        assertEquals(
            "cfG5717GGuYTChB0ux3iBT8dCMuHVRcN/h6Y0rGJSsUyW+YOiTKVcqQpLXHd/ey3tj+pVejbr+hauj6yYU7QbZfl6Arn3HBd3xBauZLe5A==",
            prepared.parameters["data"],
        )
        assertEquals(
            "ZLutskC1R653bW87omqvCCI/CPzSVlsHp2X5zQ==",
            prepared.parameters["rc4_hash__"],
        )
        assertEquals(
            "ILBiYk32SPRchaIRnRgq82urqKE=",
            prepared.parameters["signature"],
        )
    }
}
