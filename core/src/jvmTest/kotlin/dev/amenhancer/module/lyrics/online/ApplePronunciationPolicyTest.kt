package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers HLE's `ApplePronunciationPolicy` word-track half, the piece the
 * line-level pronunciation delivery was missing.
 *
 * These are pure JVM tests: the host hook only resolves Apple's members and
 * calls in here, so the device-facing rule (`wordTrack`, the official/main-word
 * compatibility test and the romanization-to-word alignment) is pinned without
 * a device.
 */
class ApplePronunciationPolicyTest {

    @Test
    fun `an aligned Apple word vector wins even when the line text is empty`() {
        // The remaining defect: HLE only reached OFFICIAL from
        // `officialLineText != null && officialWordVectorText != null`, so a
        // word-timing line whose line text Apple left empty rendered our online
        // lane. Apple's own words must win.
        val official = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = null,
            officialWordVectorText = "gu b bai sen gen",
            onlineText = "gubbai sengen",
            officialWordsCompatible = true,
        )
        assertEquals(ApplePronunciationWordTrack.OFFICIAL, official.track)
        assertEquals(null, official.pronunciation)
        assertEquals(ApplePronunciationTextSource.APPLE, official.source)

        // HLE's exact condition (line text *and* word text, aligned) is unchanged.
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.planPronunciationWords(
                officialLineText = "gu b bai sen gen",
                officialWordVectorText = "gu b bai sen gen",
                onlineText = null,
                officialWordsCompatible = true,
            ).track,
        )
    }

    @Test
    fun `our lane only fills what Apple leaves empty`() {
        // Apple's line text is preferred over ours on the main timing, exactly as
        // HLE's `mainTimingPronunciation` branch does.
        val appleLine = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = "gu b bai",
            officialWordVectorText = null,
            onlineText = "gubbai",
            officialWordsCompatible = false,
        )
        assertEquals(ApplePronunciationWordTrack.MAIN_LINE_TIMING, appleLine.track)
        assertEquals("gu b bai", appleLine.pronunciation)
        assertEquals(ApplePronunciationTextSource.APPLE, appleLine.source)

        // An unaligned Apple word vector is still Apple data: it is sliced onto
        // the main words instead of being replaced by the online lane.
        val appleWords = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = null,
            officialWordVectorText = "gu b bai",
            onlineText = "gubbai",
            officialWordsCompatible = false,
        )
        assertEquals(ApplePronunciationWordTrack.MAIN_LINE_TIMING, appleWords.track)
        assertEquals("gu b bai", appleWords.pronunciation)
        assertEquals(ApplePronunciationTextSource.APPLE, appleWords.source)

        // Only a line Apple left completely empty falls back to our lane.
        val online = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = null,
            officialWordVectorText = null,
            onlineText = "gubbai",
            officialWordsCompatible = false,
        )
        assertEquals(ApplePronunciationWordTrack.MAIN_LINE_TIMING, online.track)
        assertEquals("gubbai", online.pronunciation)
        assertEquals(ApplePronunciationTextSource.ONLINE, online.source)

        val hidden = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = null,
            officialWordVectorText = null,
            onlineText = null,
            officialWordsCompatible = false,
        )
        assertEquals(ApplePronunciationWordTrack.HIDDEN, hidden.track)
        assertEquals(null, hidden.pronunciation)
        assertEquals(ApplePronunciationTextSource.NONE, hidden.source)
    }

    @Test
    fun `wordTrack keeps Apple's own words first and hides when there is nothing`() {
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = true,
                hasOnlinePronunciation = true,
            ),
        )
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = true,
                hasOnlinePronunciation = false,
            ),
        )
        assertEquals(
            ApplePronunciationWordTrack.MAIN_LINE_TIMING,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = false,
                hasOnlinePronunciation = true,
            ),
        )
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = false,
                hasOnlinePronunciation = false,
            ),
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
