package com.example.mochi_pet.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun `update check targets current Hi Mochi repository`() {
        assertEquals("gongpx20069/hi-mochi", UPDATE_RELEASE_REPOSITORY)
        assertEquals(
            "https://api.github.com/repos/" +
                "gongpx20069/hi-mochi/releases/latest",
            LATEST_RELEASE_API,
        )
    }

    @Test
    fun `semantic versions compare numerically`() {
        assertTrue(
            SemanticVersion.parse("v1.0.10")!! >
                SemanticVersion.parse("1.0.9")!!,
        )
        assertTrue(
            SemanticVersion.parse("2.0.0")!! >
                SemanticVersion.parse("1.99.99")!!,
        )
    }

    @Test
    fun `semantic version accepts release tag`() {
        assertEquals("1.0.1", SemanticVersion.parse("v1.0.1").toString())
        assertNull(SemanticVersion.parse("1.0"))
        assertNull(SemanticVersion.parse("1.0.1-beta"))
    }
}
