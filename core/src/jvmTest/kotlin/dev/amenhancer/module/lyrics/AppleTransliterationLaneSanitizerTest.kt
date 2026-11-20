package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery rule for a transliteration lane Apple cannot render.
 *
 * The reported device shape: a Word-timed AMLL document whose head
 * `<transliteration>` holds one whole-line text per lyric and no timed
 * `<span>`. The app advertises and selects that lane — its language is on
 * the `<transliteration>` element — and then draws nothing, because a
 * Word-timed line is rendered from `getPronunciationWords()` alone.
 */
class AppleTransliterationLaneSanitizerTest {

    /** Word-timed body with a head lane that carries no timed slice at all. */
    private val wordTimedLineLevel = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja">
        <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">T1</text></translation></translations>
        <transliterations><transliteration xml:lang="ja-Latn"><text for="L1">ku ru tta</text><text for="L2">su ki</text></transliteration></transliterations>
        </iTunesMetadata></metadata></head>
        <body><div begin="00:01.000" end="00:04.000">
        <p begin="00:01.000" end="00:02.000" itunes:key="L1"><span begin="00:01.000" end="00:01.500">aa</span> <span begin="00:01.500" end="00:02.000">bb</span></p>
        <p begin="00:03.000" end="00:04.000" itunes:key="L2"><span begin="00:03.000" end="00:04.000">cc</span></p>
        </div></body></tt>
    """.trimIndent()

    /** The same document, with one timed slice per word instead of line text. */
    private val wordTimedWordSpans = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja">
        <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="00:01.000" end="00:01.500">ku</span><span begin="00:01.500" end="00:02.000">ru</span></text></transliteration></transliterations>
        </iTunesMetadata></metadata></head>
        <body><div begin="00:01.000" end="00:02.000">
        <p begin="00:01.000" end="00:02.000" itunes:key="L1"><span begin="00:01.000" end="00:01.500">aa</span> <span begin="00:01.500" end="00:02.000">bb</span></p>
        </div></body></tt>
    """.trimIndent()

    /** The same head lane on a body Apple renders line by line. */
    private val lineTimedLineLevel = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line" xml:lang="ja">
        <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <transliterations><transliteration xml:lang="ja-Latn"><text for="L1">ku ru tta</text></transliteration></transliterations>
        </iTunesMetadata></metadata></head>
        <body><div>
        <p begin="00:01.000" end="00:02.000" itunes:key="L1"><span>aa bb</span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun `a word timed document loses a line level transliteration track`() {
        val result = AppleTransliterationLaneSanitizer.sanitize(wordTimedLineLevel)

        assertFalse(result.contains("<transliterations"))
        assertFalse(result.contains("ku ru tta"))
        // Only the unrenderable lane goes: the translation lane and every byte of
        // the body are copied through.
        assertTrue(
            result.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text></translation></translations>",
            ),
        )
        assertTrue(
            result.contains(
                "<span begin=\"00:01.000\" end=\"00:01.500\">aa</span> " +
                    "<span begin=\"00:01.500\" end=\"00:02.000\">bb</span>",
            ),
        )
        assertTrue(result.contains("itunes:timing=\"Word\""))
        assertTrue(result.contains("itunes:key=\"L2\""))
    }

    @Test
    fun `a word timed document keeps a per word transliteration track`() {
        assertEquals(
            wordTimedWordSpans,
            AppleTransliterationLaneSanitizer.sanitize(wordTimedWordSpans),
        )
    }

    @Test
    fun `a line timed document is left alone`() {
        // A Line-timed song is rendered from the per-line text, which is exactly
        // what a span-less entry holds, so the lane is real data there.
        assertEquals(
            lineTimedLineLevel,
            AppleTransliterationLaneSanitizer.sanitize(lineTimedLineLevel),
        )
        // An undeclared timing over an untimed body is a Line document too.
        val undeclared = lineTimedLineLevel.replace("itunes:timing=\"Line\" ", "")
        assertEquals(undeclared, AppleTransliterationLaneSanitizer.sanitize(undeclared))
    }

    @Test
    fun `a document without a pronunciation track is untouched`() {
        val plain = wordTimedWordSpans.replace(
            "<transliterations><transliteration xml:lang=\"ja-Latn\"><text for=\"L1\">" +
                "<span begin=\"00:01.000\" end=\"00:01.500\">ku</span>" +
                "<span begin=\"00:01.500\" end=\"00:02.000\">ru</span></text>" +
                "</transliteration></transliterations>",
            "",
        )

        assertEquals(plain, AppleTransliterationLaneSanitizer.sanitize(plain))
    }

    @Test
    fun `a mixed track keeps its slot order and empties the line level entries`() {
        // Dropping one entry would shift every later reading onto the wrong
        // lyric, because the app walks the track alongside the lines.
        val mixed = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="00:01.000" end="00:02.000">ku ru</span></text><text for="L2">su ki</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div>
            <p begin="00:01.000" end="00:02.000" itunes:key="L1"><span begin="00:01.000" end="00:02.000">aa</span></p>
            <p begin="00:03.000" end="00:04.000" itunes:key="L2"><span begin="00:03.000" end="00:04.000">cc</span></p>
            </div></body></tt>
        """.trimIndent()

        val result = AppleTransliterationLaneSanitizer.sanitize(mixed)

        assertTrue(
            result.contains(
                "<text for=\"L1\"><span begin=\"00:01.000\" end=\"00:02.000\">ku ru</span></text>",
            ),
        )
        assertTrue(result.contains("<text for=\"L2\"> </text>"))
        assertFalse(result.contains("su ki"))
        assertEquals(2, Regex("<text for=").findAll(result).count())
    }

    @Test
    fun `a lane with no timed slice is dropped when another lane carries one`() {
        val twoLanes = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1">ku ru</text></transliteration><transliteration xml:lang="en-Latn"><text for="L1"><span begin="00:01.000" end="00:02.000">ku ru</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="00:01.000" end="00:02.000" itunes:key="L1"><span begin="00:01.000" end="00:02.000">aa</span></p></div></body></tt>
        """.trimIndent()

        val result = AppleTransliterationLaneSanitizer.sanitize(twoLanes)

        assertTrue(result.contains("<transliterations>"))
        assertFalse(result.contains("xml:lang=\"ja-Latn\""))
        assertTrue(result.contains("<transliteration xml:lang=\"en-Latn\">"))
    }

    @Test
    fun `a body with timed spans and no declared timing is word timed too`() {
        // AMLL sources do not declare the root timing; the converter derives it
        // from the body, so the same shape has to be recognized here.
        val undeclared = wordTimedLineLevel.replace("itunes:timing=\"Word\" ", "")

        assertFalse(
            AppleTransliterationLaneSanitizer.sanitize(undeclared).contains("<transliterations"),
        )
    }

    @Test
    fun `the strip is idempotent and never throws`() {
        val once = AppleTransliterationLaneSanitizer.sanitize(wordTimedLineLevel)

        assertEquals(once, AppleTransliterationLaneSanitizer.sanitize(once))
        assertEquals("", AppleTransliterationLaneSanitizer.sanitize(""))
        assertEquals("plain text", AppleTransliterationLaneSanitizer.sanitize("plain text"))
        val malformed = "<tt itunes:timing=\"Word\"><head><metadata><transliteration"
        assertEquals(malformed, AppleTransliterationLaneSanitizer.sanitize(malformed))
    }
}
