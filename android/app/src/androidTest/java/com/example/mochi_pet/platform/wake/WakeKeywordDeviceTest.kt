package com.example.mochi_pet.platform.wake

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.core.wake.WakeKeyword
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeKeywordDeviceTest {
    @Test
    fun selectedPhraseRecognizesSyntheticSpeechWithoutRecognizingOtherPhraseOrCommands() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val preferences = context.getSharedPreferences("mochi_wake_preferences", Context.MODE_PRIVATE)
        val original = preferences.getString("keyword", null)
        val detector = BriefingKeywordDetector(context)
        try {
            for (keyword in WakeKeyword.entries) {
                assertTrue(preferences.edit().putString("keyword", keyword.name).commit())
                for (name in listOf("english", "chinese", "english-negative", "chinese-negative")) {
                    detector.reset()
                    val speech = instrumentation.context.assets.open("wake/$name.wav").use { pcm(it.readBytes()) }
                    val samples = ShortArray(3_200) + speech + ShortArray(16_000)
                    var detected = false
                    for (start in samples.indices step 1_600) {
                        val chunk = samples.copyOfRange(start, minOf(start + 1_600, samples.size))
                        detected = detector.accept(chunk, chunk.size) || detected
                    }
                    assertEquals("$keyword / $name", name == keyword.name.lowercase(), detected)
                }
            }
        } finally {
            detector.close()
            assertTrue(preferences.edit().putString("keyword", original).commit())
        }
    }

    private fun pcm(wav: ByteArray): ShortArray {
        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).apply { position(12) }
        var validFormat = false
        while (buffer.remaining() >= 8) {
            val tag = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
            val size = buffer.int
            require(size >= 0 && size <= buffer.remaining())
            val start = buffer.position()
            when (tag) {
                "fmt " -> {
                    require(size >= 16 && buffer.short.toInt() == 1 && buffer.short.toInt() == 1)
                    require(buffer.int == 16_000 && buffer.int == 32_000)
                    require(buffer.short.toInt() == 2 && buffer.short.toInt() == 16)
                    validFormat = true
                }
                "data" -> {
                    require(validFormat && size % 2 == 0)
                    return ShortArray(size / 2) { buffer.short }
                }
            }
            buffer.position(start + size + size % 2)
        }
        error("Fixture has no PCM data")
    }
}
