package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the decisions HLE's native-model pronunciation
 * delivery makes: the third-party fallback language, Apple's language
 * selection, and the `has*`/`set*` availability resolution. These are the
 * halves that must stay identical to HLE; the reflection halves only resolve
 * the app's members and call in here.
 */
class NativeLyricModelPolicyTest {

    private fun fallback(
        system: String?,
        enabled: Boolean = true,
        hasOnline: Boolean = true,
        hideMandarin: Boolean = false,
        languages: Collection<String> = listOfNotNull(system),
        genre: String? = null,
    ) = NativeLyricModelPolicy.thirdPartyPronunciationFallbackLanguage(
        systemLanguage = system,
        enabled = enabled,
        hasOnlinePronunciation = hasOnline,
        hideMandarinPinyin = hideMandarin,
        pronunciationLanguages = languages,
        genre = genre,
    )

    @Test
    fun `the fallback is the system language only when it is a Latin tag`() {
        assertEquals("ja-Latn", fallback(system = "ja-Latn"))
        assertEquals("und-Latn", fallback(system = "ja"))
        assertEquals("und-Latn", fallback(system = "en"))
        assertEquals("und-Latn", fallback(system = null))
    }

    @Test
    fun `the fallback is gated by the switch the store and the Mandarin rule`() {
        assertNull(fallback(system = "ja-Latn", enabled = false))
        assertNull(fallback(system = "ja-Latn", hasOnline = false))
        // A Mandarin pronunciation language hides it outright.
        assertNull(fallback(system = "cmn-Latn", hideMandarin = true))
        // A Mandarin genre hides it; a Cantonese genre never does.
        assertNull(fallback(system = "en", hideMandarin = true, genre = "Mandopop"))
        assertEquals("und-Latn", fallback(system = "en", hideMandarin = true, genre = "Cantopop"))
        // Unknown genre hides nothing.
        assertEquals("und-Latn", fallback(system = "en", hideMandarin = true, genre = null))
    }

    private fun select(
        system: String?,
        apple: List<String>,
        onlineFallback: String?,
    ) = NativeLyricModelPolicy.selectLanguage(
        systemMatch = system,
        appleLanguages = apple,
        onlineFallbackLanguage = onlineFallback,
    )

    @Test
    fun `language selection keeps the system match then Apple then the online fallback`() {
        assertEquals(
            "ja-Latn",
            select(system = "ja-Latn", apple = listOf("ko-Latn"), onlineFallback = "und-Latn"),
        )
        assertEquals(
            "ko-Latn",
            select(system = "ja", apple = listOf("ja", "ko-Latn"), onlineFallback = "und-Latn"),
        )
        assertEquals(
            "und-Latn",
            select(system = "ja", apple = emptyList(), onlineFallback = "und-Latn"),
        )
        assertNull(select(system = "ja", apple = emptyList(), onlineFallback = null))
    }

    private fun translationAvailability(
        original: Boolean,
        enabled: Boolean,
        online: Boolean,
    ) = NativeLyricModelPolicy.hasTranslationAvailability(
        original = original,
        enabled = enabled,
        hasOnlineTranslation = online,
    )

    @Test
    fun `translation availability never withdraws Apple's own value`() {
        assertTrue(translationAvailability(original = true, enabled = false, online = false))
        assertTrue(translationAvailability(original = true, enabled = true, online = false))
        assertTrue(translationAvailability(original = false, enabled = true, online = true))
        assertFalse(translationAvailability(original = false, enabled = true, online = false))
    }

    private fun pronunciationAvailability(
        original: Boolean,
        enabled: Boolean = true,
        online: Boolean,
        validOfficial: Boolean,
        mandarinHidden: Boolean,
    ) = NativeLyricModelPolicy.hasPronunciationAvailability(
        original = original,
        enabled = enabled,
        hasOnlinePronunciation = online,
        hasValidOfficialPronunciation = validOfficial,
        mandarinHidden = mandarinHidden,
    )

    @Test
    fun `pronunciation availability hides Mandarin and advertises the online lane`() {
        assertFalse(
            pronunciationAvailability(original = true, online = true, validOfficial = true, mandarinHidden = true),
        )
        assertTrue(
            pronunciationAvailability(original = false, online = true, validOfficial = false, mandarinHidden = false),
        )
        assertFalse(
            pronunciationAvailability(original = false, online = false, validOfficial = false, mandarinHidden = false),
        )
        // Apple's own valid romanization survives even with the feature off.
        assertTrue(
            pronunciationAvailability(
                original = true,
                enabled = false,
                online = false,
                validOfficial = true,
                mandarinHidden = false,
            ),
        )
        assertFalse(
            pronunciationAvailability(
                original = true,
                enabled = false,
                online = false,
                validOfficial = false,
                mandarinHidden = false,
            ),
        )
    }

    @Test
    fun `the third-party tag is HLE's script-neutral Latin tag`() {
        assertEquals("und-Latn", NativeLyricModelPolicy.THIRD_PARTY_PRONUNCIATION_LANGUAGE)
    }
}
