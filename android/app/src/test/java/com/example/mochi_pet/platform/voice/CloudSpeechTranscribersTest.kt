package com.example.mochi_pet.platform.voice

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechTranscribersTest {
    @Test
    fun `azure URL uses short audio recognition endpoint`() {
        val url = azureSpeechUrl(
            endpoint = "https://example.cognitiveservices.azure.com/",
            language = "zh-CN",
        )

        assertEquals(
            "/stt/speech/recognition/conversation/cognitiveservices/v1",
            url.encodedPath,
        )
        assertEquals("zh-CN", url.queryParameter("language"))
        assertEquals("simple", url.queryParameter("format"))
    }

    @Test
    fun `azure successful response returns display text`() {
        assertEquals(
            "明天下午开会。",
            parseAzureTranscript(
                """
                {
                  "RecognitionStatus": "Success",
                  "DisplayText": "明天下午开会。"
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `iFlytek signed URL contains bounded authentication values`() {
        val url = iFlytekSignedUrl(
            apiKey = "test-api-key",
            apiSecret = "test-api-secret",
            clock = Clock.fixed(
                Instant.parse("2026-08-04T06:00:00Z"),
                ZoneOffset.UTC,
            ),
        )

        assertEquals("iat-api.xfyun.cn", url.host)
        assertEquals("/v2/iat", url.encodedPath)
        assertEquals("iat-api.xfyun.cn", url.queryParameter("host"))
        assertEquals(
            "Tue, 04 Aug 2026 06:00:00 GMT",
            url.queryParameter("date"),
        )
        val authorization = String(
            Base64.getDecoder().decode(
                checkNotNull(url.queryParameter("authorization")),
            ),
        )
        assertTrue(authorization.contains("""api_key="test-api-key""""))
        assertTrue(authorization.contains("""algorithm="hmac-sha256""""))
        assertTrue(authorization.contains("""headers="host date request-line""""))
    }

    @Test
    fun `iFlytek first frame enables provider endpoint detection`() {
        val frame = iFlytekFrame(
            status = 0,
            audio = "audio",
            appId = "app-id",
            locale = Locale.SIMPLIFIED_CHINESE,
        )

        assertEquals(
            700,
            frame["business"]
                ?.jsonObject
                ?.get("vad_eos")
                ?.jsonPrimitive
                ?.int,
        )
    }
}
