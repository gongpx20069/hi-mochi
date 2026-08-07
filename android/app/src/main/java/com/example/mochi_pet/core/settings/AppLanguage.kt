package com.example.mochi_pet.core.settings

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class AppLanguage(
    val languageTag: String?,
) {
    SYSTEM(null),
    CHINESE("zh"),
    ENGLISH("en"),
    ;

    fun apply(context: Context) {
        AppCompatDelegate.setApplicationLocales(
            languageTag?.let(LocaleListCompat::forLanguageTags)
                ?: LocaleListCompat.getEmptyLocaleList(),
        )
        context.sendBroadcast(
            Intent(ACTION_APP_LANGUAGE_CHANGED)
                .setPackage(context.packageName),
        )
    }

    companion object {
        fun current(): AppLanguage =
            fromLanguageTags(
                AppCompatDelegate.getApplicationLocales().toLanguageTags(),
            )

        fun fromLanguageTags(languageTags: String): AppLanguage {
            val firstTag = languageTags
                .substringBefore(',')
                .trim()
                .lowercase()
            return when {
                firstTag.isEmpty() -> SYSTEM
                firstTag == "zh" || firstTag.startsWith("zh-") -> CHINESE
                else -> ENGLISH
            }
        }

        fun resolveContentLocale(
            locale: Locale = Locale.getDefault(),
        ): Locale =
            if (locale.language == Locale.CHINESE.language) {
                Locale.SIMPLIFIED_CHINESE
            } else {
                Locale.ENGLISH
            }

        fun localizedContext(context: Context): Context {
            val locale = when (current()) {
                SYSTEM -> resolveContentLocale(
                    Resources.getSystem()
                        .configuration
                        .locales[0],
                )
                CHINESE -> Locale.SIMPLIFIED_CHINESE
                ENGLISH -> Locale.ENGLISH
            }
            val configuration = Configuration(
                context.resources.configuration,
            ).apply {
                setLocale(locale)
            }
            return context.createConfigurationContext(configuration)
        }

        const val ACTION_APP_LANGUAGE_CHANGED =
            "com.example.mochi_pet.action.APP_LANGUAGE_CHANGED"
    }
}
