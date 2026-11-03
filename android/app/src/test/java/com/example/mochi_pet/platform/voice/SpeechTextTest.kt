package com.example.mochi_pet.platform.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test
    fun `only inline link destinations are omitted`() {
        assertEquals(
            "\u67e5\u770b\u5b98\u7f51\uff0c\u6216\u9605\u8bfb\u6587\u6863\u3002 https://example.org",
            speechText(
                "\u67e5\u770b[\u5b98\u7f51](https://example.com)\uff0c" +
                    "\u6216\u9605\u8bfb[\u6587\u6863](https://example.com/docs)\u3002 https://example.org",
            ),
        )
    }

    @Test
    fun `balanced destinations and optional titles do not leak URL fragments`() {
        assertEquals(
            "Guide, source, notes.",
            speechText(
                "[Guide](https://example.com/a_(b(c))?q=1#part), " +
                    "[source](<https://example.com/a(b)> \"Tooltip (not spoken)\"), " +
                    "[notes](https://example.org 'Another title').",
            ),
        )
    }

    @Test
    fun `labels preserve brackets and escaped punctuation`() {
        assertEquals(
            "A [short] guide and closing ] bracket",
            speechText("""[A [short] guide](https://example.com/a\(b\)) and [closing \] bracket](/guide)"""),
        )
    }

    @Test
    fun `plain text malformed links and reference links are unchanged`() {
        val text = "Plain [text], [broken](https://example.com/a_(b), " +
            "[bad](a b), [reference][guide], https://example.org."
        assertEquals(text, speechText(text))
    }

    @Test
    fun `literal code escaped links and images are unchanged`() {
        val text = """`[code](https://example.com)` and ``[code with ` tick](url)`` """ +
            """and \[escaped](https://example.com) and ![image](https://example.com/image.png)"""
        assertEquals(text, speechText(text))
    }

    @Test
    fun `empty label has no destination to speak`() {
        assertEquals("Before  after", speechText("Before [](https://example.com) after"))
    }
}
