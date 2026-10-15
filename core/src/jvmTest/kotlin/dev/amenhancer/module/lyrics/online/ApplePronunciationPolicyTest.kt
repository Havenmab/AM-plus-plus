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
 * a device. Apple's own lanes always win; the module's displayed-document lane
 * only ever fills a line Apple left empty, on Apple's main word timing.
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
    fun `an available text lane is rendered on the main timing`() {
        // Apple supplied no usable vector, but the module's own displayed
        // document carries romanization for the line: the main-timing track
        // renders that text on Apple's existing words.
        val plan = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = null,
            officialWordVectorText = null,
            onlineText = "ki mi no na wa",
            officialWordsCompatible = false,
        )
        assertEquals(ApplePronunciationWordTrack.MAIN_LINE_TIMING, plan.track)
        assertEquals("ki mi no na wa", plan.pronunciation)
        assertEquals(ApplePronunciationTextSource.ONLINE, plan.source)
        // Apple's own line text is preferred over the module's when both exist.
        val appleText = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = "ki mi no na wa",
            officialWordVectorText = null,
            onlineText = "online text",
            officialWordsCompatible = false,
        )
        assertEquals(ApplePronunciationWordTrack.MAIN_LINE_TIMING, appleText.track)
        assertEquals("ki mi no na wa", appleText.pronunciation)
        assertEquals(ApplePronunciationTextSource.APPLE, appleText.source)
        // Nothing at all stays HIDDEN.
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.planPronunciationWords(
                officialLineText = null,
                officialWordVectorText = null,
                onlineText = null,
                officialWordsCompatible = false,
            ).track,
        )
    }

    @Test
    fun `wordTrack has the Apple, main-timing and hidden branches`() {
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.wordTrack(hasValidOfficialPronunciation = true),
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
            ApplePronunciationPolicy.wordTrack(hasValidOfficialPronunciation = false),
        )
        assertEquals(
            listOf("OFFICIAL", "MAIN_LINE_TIMING", "HIDDEN"),
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
    fun `matches each Japanese word to its own timed transliteration span`() {
        // 衣装に仕掛けてる, kanji words with a kana particle: the document times
        // one span per lyric word, so every word carries its own romaji — never
        // the whole line on the last word (the reported pile-up).
        assertEquals(
            listOf(
                PronunciationWordMatch(wordIndex = 0, text = "ishou"),
                PronunciationWordMatch(wordIndex = 1, text = "ni"),
                PronunciationWordMatch(wordIndex = 2, text = "shikake"),
                PronunciationWordMatch(wordIndex = 3, text = "teru"),
            ),
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(
                    PronunciationWordTiming(1_000L, 1_500L),
                    PronunciationWordTiming(1_500L, 1_700L),
                    PronunciationWordTiming(1_700L, 2_300L),
                    PronunciationWordTiming(2_300L, 2_800L),
                ),
                spans = listOf(
                    NativePronunciationSpan(1_000L, 1_500L, "ishou"),
                    NativePronunciationSpan(1_500L, 1_700L, "ni"),
                    NativePronunciationSpan(1_700L, 2_300L, "shikake"),
                    NativePronunciationSpan(2_300L, 2_800L, "teru"),
                ),
            ),
        )
    }

    @Test
    fun `concatenates several finer spans owned by one word`() {
        // The document may time the romanization finer than the lyric words: the
        // word owns both slices and shows them together, in document order.
        assertEquals(
            listOf(PronunciationWordMatch(wordIndex = 0, text = "roma")),
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(PronunciationWordTiming(0L, 1_000L)),
                spans = listOf(
                    NativePronunciationSpan(0L, 500L, "ro"),
                    NativePronunciationSpan(500L, 1_000L, "ma"),
                ),
            ),
        )
    }

    @Test
    fun `a background vocal's own slice is matched against its own words`() {
        // The L19 shape: the lyric's words/spans and the `x-bg` group's
        // words/spans are two independent lists. Matching the main list must give
        // the last main word its own `boon` and nothing else — the old flattened
        // lane put the background's `(boon)` on that word, rendering
        // `boon(boon)`. The background's word gets `(boon)` from its own list.
        val mainWords = listOf(
            PronunciationWordTiming(1_000L, 1_500L),
            PronunciationWordTiming(1_500L, 2_000L),
        )
        val mainSpans = listOf(
            NativePronunciationSpan(1_000L, 1_500L, "ri"),
            NativePronunciationSpan(1_500L, 2_000L, "boon"),
        )
        val backgroundWords = listOf(PronunciationWordTiming(2_100L, 2_500L))
        val backgroundSpans = listOf(NativePronunciationSpan(2_100L, 2_500L, "(boon)"))

        assertEquals(
            listOf(
                PronunciationWordMatch(wordIndex = 0, text = "ri"),
                PronunciationWordMatch(wordIndex = 1, text = "boon"),
            ),
            ApplePronunciationPolicy.matchPronunciationSpans(mainWords, mainSpans),
        )
        assertEquals(
            listOf(PronunciationWordMatch(wordIndex = 0, text = "(boon)")),
            ApplePronunciationPolicy.matchPronunciationSpans(backgroundWords, backgroundSpans),
        )
        // Even if a background slice is handed to the main list by mistake and
        // overlaps the last word, the policy never duplicates it across words.
        assertEquals(
            listOf(
                PronunciationWordMatch(wordIndex = 0, text = "ri"),
                PronunciationWordMatch(wordIndex = 1, text = "boon(boon)"),
            ),
            ApplePronunciationPolicy.matchPronunciationSpans(
                mainWords,
                mainSpans + NativePronunciationSpan(1_900L, 2_100L, "(boon)"),
            ),
        )
    }

    @Test
    fun `a span that crosses a word boundary is never duplicated`() {
        // A line- or group-level span belongs to no single word: it is dropped,
        // so a whole-line string can neither pile onto one word nor repeat on all.
        assertEquals(
            emptyList<PronunciationWordMatch>(),
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(
                    PronunciationWordTiming(1_000L, 1_500L),
                    PronunciationWordTiming(1_500L, 2_000L),
                ),
                spans = listOf(NativePronunciationSpan(1_000L, 2_000L, "ishouni")),
            ),
        )
    }

    @Test
    fun `an unmatched word keeps its own text and is absent from the result`() {
        // Only the particle has a span; its neighbours must not borrow it.
        assertEquals(
            listOf(PronunciationWordMatch(wordIndex = 1, text = "ni")),
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(
                    PronunciationWordTiming(1_000L, 1_500L),
                    PronunciationWordTiming(1_500L, 1_700L),
                    PronunciationWordTiming(1_700L, 2_300L),
                ),
                spans = listOf(NativePronunciationSpan(1_500L, 1_700L, "ni")),
            ),
        )
    }

    @Test
    fun `a full-width leading space is normalized away`() {
        // U+3000 survives `String.trim` and `\s`; the span text must still reach
        // the word with no stray edge space (the reported 糸一つほどけば case).
        assertEquals(
            listOf(PronunciationWordMatch(wordIndex = 0, text = "itohitotsu")),
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(PronunciationWordTiming(1_000L, 2_000L)),
                spans = listOf(
                    NativePronunciationSpan(1_000L, 2_000L, "\u3000itohitotsu\u3000"),
                ),
            ),
        )
        // A span with nothing but spaces (NBSP included) matches no word.
        assertEquals(
            emptyList<PronunciationWordMatch>(),
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(PronunciationWordTiming(1_000L, 2_000L)),
                spans = listOf(NativePronunciationSpan(1_000L, 2_000L, " \u00A0 ")),
            ),
        )
    }

    @Test
    fun `there is nothing to match without words or spans`() {
        assertTrue(
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = emptyList(),
                spans = listOf(NativePronunciationSpan(0L, 1_000L, "a")),
            ).isEmpty(),
        )
        assertTrue(
            ApplePronunciationPolicy.matchPronunciationSpans(
                words = listOf(PronunciationWordTiming(0L, 1_000L)),
                spans = emptyList(),
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
