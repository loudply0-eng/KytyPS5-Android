package com.kytyps5.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherI18nTest {
    @Test
    fun exposesTheRequestedSixteenLanguagesInStableOrder() {
        assertEquals(
            listOf(
                "ar", "en", "es", "fr", "de", "pt-BR", "ru", "zh-CN",
                "ja", "ko", "hi", "tr", "it", "id", "ur", "zh-TW",
            ),
            LauncherI18n.languages.map { it.code },
        )
    }

    @Test
    fun normalizesUnknownOrMissingLanguageToEnglish() {
        assertEquals("en", LauncherI18n.normalize(null))
        assertEquals("en", LauncherI18n.normalize(""))
        assertEquals("en", LauncherI18n.normalize("xx"))
        assertEquals("pt-BR", LauncherI18n.normalize("pt-BR"))
        assertEquals("zh-TW", LauncherI18n.normalize("zh-TW"))
    }

    @Test
    fun everyTranslationTableHasExactlyOneValuePerLanguage() {
        assertTrue(LauncherI18n.hasCompleteTranslationTables())
    }

    @Test
    fun settingsAndUpdaterCoreLabelsAreTranslatedForEveryLanguage() {
        val keys = listOf(
            "Settings",
            "Updates",
            "Language",
            "Choose language",
            "Interface color",
            "About",
            "Check for updates",
            "Download and install",
            "Cancel",
            "Play",
            "Metadata only",
            "PKG metadata • not installable",
            "exFAT image",
            "exFAT disk image",
        )
        LauncherI18n.languages.forEach { language ->
            keys.forEach { key ->
                val translated = LauncherI18n.translate(key, language.code)
                if (language.code == "en") {
                    // English is the source language, so an unchanged value is expected.
                    assertEquals("English source text changed for '$key'", key, translated)
                } else {
                    assertNotEquals(
                        "Missing " + language.code + " translation for '" + key + "'",
                        key,
                        translated,
                    )
                }
            }
        }
    }

    @Test
    fun rightToLeftIsEnabledOnlyForArabicAndUrdu() {
        assertTrue(LauncherI18n.isRtl("ar"))
        assertTrue(LauncherI18n.isRtl("ur"))
        listOf("en", "es", "fr", "de", "pt-BR", "ru", "zh-CN", "ja", "ko", "hi", "tr", "it", "id", "zh-TW")
            .forEach { assertFalse(it + " must be left-to-right", LauncherI18n.isRtl(it)) }
    }
}
