package com.example.mochi_pet.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolDirectoryTest {
    @Test fun `descriptions normalize whitespace and preserve the inclusive boundary`() {
        assertEquals("Read a page", SkillToolDirectory.description(" \nRead\t a\u00a0page\r\n"))
        assertEquals("x".repeat(100), SkillToolDirectory.description("x".repeat(100)))
        assertEquals("x".repeat(97) + "...", SkillToolDirectory.description("x".repeat(101)))
        val truncated = SkillToolDirectory.description("Read records ".repeat(20))
        assertTrue(truncated.length <= 100)
        assertTrue(truncated.endsWith("records...") || truncated.endsWith("Read..."))
        assertFalse(truncated.contains("\n"))
    }

    @Test fun `truncation never splits supplementary Unicode characters`() {
        val text = "\uD83D\uDE00".repeat(101)
        val truncated = SkillToolDirectory.description(text)
        assertEquals(100, truncated.codePointCount(0, truncated.length))
        assertEquals("\uD83D\uDE00".repeat(97) + "...", truncated)
    }
}
