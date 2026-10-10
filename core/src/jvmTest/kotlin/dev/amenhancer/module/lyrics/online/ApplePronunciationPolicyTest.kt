package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Apple-only pronunciation word-track half the line-level delivery
 * was missing.
 *
 * These are pure JVM tests: the host hook only resolves Apple's members and
 * calls in here, so the device-facing rule (`wordTrack`, the official/main-word
 * compatibility test and the romanization-to-word alignment) is pinned without
 * a device. The third-party text lane is gone: the policy has exactly two
 * outcomes, `OFFICIAL` and `HIDDEN`.
 */
class ApplePronunciationPolicyTest {

    @Test
    fun `an aligned Apple word vector is the only OFFICIAL source`() {
        // Apple's own words win even when the line text is empty: the word
        // vector itself is Apple data.
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.planPronunciationWords(
                officialWordVectorText = "gu b bai sen gen",
                officialWordsCompatible = true,
            ),
        )
    }

    @Test
    fun `without an aligned Apple word vector the line is HIDDEN`() {
        // Apple supplied nothing for the line.
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.planPronunciationWords(
                officialWordVectorText = null,
                officialWordsCompatible = true,
            ),
        )
        // Apple's word vector exists but its timeline is incompatible with the
        // main line; nothing may be substituted for it.
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.planPronunciationWords(
                officialWordVectorText = "gu b bai",
                officialWordsCompatible = false,
            ),
        )
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.planPronunciationWords(
                officialWordVectorText = null,
                officialWordsCompatible = false,
            ),
        )
    }

    @Test
    fun `wordTrack has no third-party branch`() {
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.wordTrack(hasValidOfficialPronunciation = true),
        )
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.wordTrack(hasValidOfficialPronunciation = false),
        )
        // The removed HLE third-party track can never be produced.
        assertEquals(
            listOf("OFFICIAL", "HIDDEN"),
            ApplePronunciationWordTrack.entries.map { it.name },
        )
    }

    @Test
    fun `official words are only compatible when every visible main begin has a key`() {
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(0, 100, 200),
                pronunciationWordBegins = listOf(0, 100, 200, 300),
            ),
        )
        // The exact 6.5.x regression: a pronunciation timeline missing one main
        // word cannot be used, even though both vectors are non-empty.
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(0, 100, 200),
                pronunciationWordBegins = listOf(0, 200),
            ),
        )
        // Negative begins are Apple's "no timing" sentinel and are ignored.
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(0, -1, 200),
                pronunciationWordBegins = listOf(0, 200),
            ),
        )
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = emptyList(),
                pronunciationWordBegins = listOf(0),
            ),
        )
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(0),
                pronunciationWordBegins = emptyList(),
            ),
        )
        // Duplicate begins on the main line are collapsed, as HLE does.
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(0, 0, 100),
                pronunciationWordBegins = listOf(100, 0),
            ),
        )
    }

    @Test
    fun `displaySegments weights East Asian words by character count`() {
        // HLE's own example: 4 native words must carry 5 Cantonese syllables, so
        // 潇洒 counts twice and the later syllables stay aligned.
        assertEquals(
            listOf("siu1 sa2", "dik1", "fong3", "pei3"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "siu1 sa2 dik1 fong3 pei3",
                mainWordTexts = listOf("潇洒", "的", "放", "屁"),
            ),
        )
    }

    @Test
    fun `displaySegments gives non-East-Asian words one unit each`() {
        assertEquals(
            listOf("hello", "world"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "hello world",
                mainWordTexts = listOf("hello", "world"),
            ),
        )
        assertEquals(
            listOf("ab", "cd"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "ab cd",
                mainWordTexts = listOf("ab", "cd"),
            ),
        )
    }

    @Test
    fun `displaySegments strips HTML before counting and falls back when unmeasurable`() {
        assertEquals(
            listOf("a", "b", "c"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "a b c",
                mainWordTexts = listOf("<b>潇</b>", "洒", "的"),
            ),
        )
        // No word carries a letter or digit: every word gets one unit, which
        // distributes the tokens as evenly as HLE can.
        assertEquals(
            listOf("a", "b"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "a b",
                mainWordTexts = listOf("—", "·"),
            ),
        )
    }

    @Test
    fun `displaySegments is empty when there is nothing to align`() {
        assertTrue(
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "a b",
                mainWordTexts = emptyList(),
            ).isEmpty(),
        )
        assertTrue(
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "   ",
                mainWordTexts = listOf("潇洒"),
            ).isEmpty(),
        )
    }

    @Test
    fun `nonNullDisplayText never returns null`() {
        assertEquals("", ApplePronunciationPolicy.nonNullDisplayText(null))
        assertEquals("", ApplePronunciationPolicy.nonNullDisplayText(""))
        assertEquals("ro ma ji", ApplePronunciationPolicy.nonNullDisplayText("ro ma ji"))
    }
}
