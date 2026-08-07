package com.example.mochi_pet.core.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `empty application locales follow the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
    }

    @Test
    fun `Chinese application locales select Chinese`() {
        assertEquals(
            AppLanguage.CHINESE,
            AppLanguage.fromLanguageTags("zh-CN"),
        )
        assertEquals(
            AppLanguage.CHINESE,
            AppLanguage.fromLanguageTags("zh-Hant-TW"),
        )
    }

    @Test
    fun `non-Chinese application locales select English`() {
        assertEquals(
            AppLanguage.ENGLISH,
            AppLanguage.fromLanguageTags("en-US"),
        )
        assertEquals(
            AppLanguage.ENGLISH,
            AppLanguage.fromLanguageTags("fr-FR"),
        )
    }

    @Test
    fun `system content resolves Chinese or English only`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            AppLanguage.resolveContentLocale(Locale.TRADITIONAL_CHINESE),
        )
        assertEquals(
            Locale.ENGLISH,
            AppLanguage.resolveContentLocale(Locale.FRENCH),
        )
    }
}
