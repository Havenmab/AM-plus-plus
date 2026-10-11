package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for reading an Apple document back into the writer's line
 * model: word spans and `<p>` timing, the head translation/transliteration
 * lanes looked up by `itunes:key`, entity decoding, and fail-open on garbage.
 */
class AppleLyricTtmlReaderTest {

    @Test
    fun `reads word spans and both head lanes by itunes key`() {
        val lines = AppleLyricTtmlReader.read(WORD_DOCUMENT)

        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals(1_000L, first.begin)
        assertEquals(2_500L, first.end)
        assertEquals("Hello world", first.text)
        assertEquals(listOf(1_000L to "Hello", 1_700L to "world"), first.words.map { it.begin to it.text })
        assertEquals(listOf(1_700L, 2_500L), first.words.map { it.end })
        assertEquals("你好，世界", first.translation)
        assertEquals("haro", first.romanization)

        val second = lines[1]
        assertEquals(2_500L, second.begin)
        assertEquals(5_000L, second.end)
        assertEquals("Second line", second.text)
        assertEquals("第二行", second.translation)
        assertNull(second.romanization)
    }

    @Test
    fun `a line timed paragraph keeps no word spans`() {
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Line" xml:lang="ja">""" +
                """<body><div><p begin="0:01.000" end="0:02.500" itunes:key="L1">Plain line</p>""" +
                "</div></body></tt>"

        val line = AppleLyricTtmlReader.read(ttml).single()

        assertEquals(1_000L, line.begin)
        assertEquals(2_500L, line.end)
        assertEquals("Plain line", line.text)
        assertTrue(line.words.isEmpty())
    }

    @Test
    fun `flattens a background vocal group to its syllables`() {
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word" xml:lang="ja">""" +
                """<body><div><p begin="1.000" end="3.000" itunes:key="L1">""" +
                """<span begin="1.000" end="2.000">Main</span> """ +
                """<span ttm:role="x-bg" begin="2.000" end="3.000">""" +
                """<span begin="2.000" end="2.400">back</span>""" +
                """<span begin="2.400" end="3.000">ing</span></span></p></div></body></tt>"""

        val line = AppleLyricTtmlReader.read(ttml).single()

        assertEquals(listOf("Main", "back", "ing"), line.words.map { it.text })
        assertEquals("Main backing", line.text)
    }

    @Test
    fun `decodes entities in lyric and lane text`() {
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Line" xml:lang="ja">""" +
                """<head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">""" +
                """<translations><translation type="subtitle" xml:lang="zh-Hans">""" +
                """<text for="L1">A &amp; B &#60;C&#62;</text></translation></translations>""" +
                "</iTunesMetadata></metadata></head>" +
                """<body><div><p begin="1.000" end="2.000" itunes:key="L1">rock &amp; roll</p>""" +
                "</div></body></tt>"

        val line = AppleLyricTtmlReader.read(ttml).single()

        assertEquals("rock & roll", line.text)
        assertEquals("A & B <C>", line.translation)
    }

    @Test
    fun `a missing key falls back to document order`() {
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Line" xml:lang="ja">""" +
                """<body><div><p begin="1.000" end="2.000">First</p>""" +
                """<p begin="2.000" end="3.000">Second</p></div></body></tt>"""

        assertEquals(listOf("First", "Second"), AppleLyricTtmlReader.read(ttml).map { it.text })
    }

    @Test
    fun `malformed markup yields no lines instead of throwing`() {
        assertTrue(AppleLyricTtmlReader.read("not ttml at all").isEmpty())
        assertTrue(AppleLyricTtmlReader.read("").isEmpty())
    }

    @Test
    fun `parses Apple clocks and rejects garbage`() {
        assertEquals(34_339L, AppleLyricTtmlReader.clockMs("0:34.339"))
        assertEquals(3_723_456L, AppleLyricTtmlReader.clockMs("1:02:03.456"))
        assertEquals(12_300L, AppleLyricTtmlReader.clockMs("12.3s"))
        assertEquals(1_500L, AppleLyricTtmlReader.clockMs("1.5"))
        assertNull(AppleLyricTtmlReader.clockMs("later"))
        assertNull(AppleLyricTtmlReader.clockMs(null))
    }

    /**
     * The two places a source can carry romanization, counted separately for the
     * custom-document diagnostic: an inline `ttm:role="x-roman"` span (not a role
     * the 1606 parser knows) and a head `<transliteration>` entry keyed by `for`.
     */
    @Test
    fun `counts inline roman spans and head entry keys`() {
        val document = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                <transliterations><transliteration xml:lang="ja-Latn">
                  <text for="L1">haro</text><text for="L2">wārudo</text>
                </transliteration></transliterations>
              </iTunesMetadata></metadata></head>
              <body><div>
                <p begin="1.0" end="2.0" itunes:key="L1"><span begin="1.0" end="2.0">世界</span>
                  <span ttm:role="x-roman">haro</span></p>
                <p begin="2.0" end="3.0" itunes:key="L2"><span begin="2.0" end="3.0">ワールド</span>
                  <span ttm:role='x-roman'>wārudo</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        assertEquals(listOf("L1", "L2"), AppleLyricTtmlReader.transliterationKeys(document))
        assertEquals(2, AppleLyricTtmlReader.inlineRomanizationSpans(document))
        // Apple's own document carries a head entry but no inline span.
        assertEquals(0, AppleLyricTtmlReader.inlineRomanizationSpans(WORD_DOCUMENT))
        assertEquals(listOf("L1"), AppleLyricTtmlReader.transliterationKeys(WORD_DOCUMENT))
        assertEquals(emptyList<String>(), AppleLyricTtmlReader.transliterationKeys("not ttml"))
    }

    /**
     * A Word-timed head entry carries one `<span begin end>` per lyric word (AMLL
     * writes them inline as `x-roman`, which the converter migrates verbatim).
     * The line string stays the concatenation for the line-lane fallback; the word
     * getter needs the timed slices, which the old reader flattened away.
     */
    @Test
    fun `reads a head transliteration entry's timed word slices`() {
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" """ +
                """xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja">""" +
                """<head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">""" +
                """<transliterations><transliteration xml:lang="ja-Latn">""" +
                """<text for="L1"><span begin="1.000" end="1.500">ishou</span>""" +
                """<span begin="1.500" end="1.700">ni</span></text>""" +
                """</transliteration></transliterations>""" +
                """</iTunesMetadata></metadata></head>""" +
                """<body><div><p begin="1.000" end="1.700" itunes:key="L1">""" +
                """<span begin="1.000" end="1.500">衣装</span>""" +
                """<span begin="1.500" end="1.700">に</span></p></div></body></tt>"""

        val line = AppleLyricTtmlReader.read(ttml).single()

        assertEquals("ishouni", line.romanization)
        assertEquals(
            listOf(1_000L to "ishou", 1_500L to "ni"),
            line.romanizationSpans.map { it.begin to it.text },
        )
        assertEquals(listOf(1_500L, 1_700L), line.romanizationSpans.map { it.end })
        // A line-level entry (WORD_DOCUMENT's `<text for="L1">haro</text>`) keeps
        // no slices: there is no word-level lane to match.
        assertTrue(AppleLyricTtmlReader.read(WORD_DOCUMENT).first().romanizationSpans.isEmpty())
    }

    @Test
    fun `a line's own p timing wins over a background span that ends later`() {
        // Apple's own Word-timed documents always end the `<p>` where its last span
        // ends, but an AMLL document can put a background vocal *after* the lyric
        // line. The app's native line model then carries the `<p>` timing
        // (`LyricsTimingParser::make` reads the element's own begin/end), so a
        // reader that took the line's end from its last span would key the line at
        // 3.400 and miss the native line at 3.000 for good.
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" """ +
                """itunes:timing="Word" xml:lang="ja">""" +
                """<body><div><p begin="1.000" end="3.000" itunes:key="L1">""" +
                """<span begin="1.000" end="2.000">Main</span>""" +
                """<span ttm:role="x-bg"><span begin="3.100" end="3.400">(bon)</span></span>""" +
                """</p></div></body></tt>"""

        val line = AppleLyricTtmlReader.read(ttml).single()

        assertEquals(1_000L, line.begin)
        assertEquals(3_000L, line.end)
        // The background syllable is still flattened into the word list.
        assertEquals(listOf(1_000L, 3_100L), line.words.map { it.begin })
    }

    @Test
    fun `a paragraph with no timing falls back to its timed spans`() {
        val ttml =
            """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word" xml:lang="ja">""" +
                """<body><div><p itunes:key="L1">""" +
                """<span begin="1.000" end="1.500">a</span>""" +
                """<span begin="1.500" end="2.000">b</span></p></div></body></tt>"""

        val line = AppleLyricTtmlReader.read(ttml).single()

        assertEquals(1_000L, line.begin)
        assertEquals(2_000L, line.end)
    }

    private companion object {
        val WORD_DOCUMENT = """
            <?xml version='1.0' encoding='utf-8'?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word" xml:lang="ja">
              <head><metadata>
                <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                  <translations><translation type="subtitle" xml:lang="zh-Hans">
                    <text for="L1">你好，世界</text>
                    <text for="L2">第二行</text>
                  </translation></translations>
                  <transliterations><transliteration xml:lang="ja-Latn">
                    <text for="L1">haro</text>
                  </transliteration></transliterations>
                </iTunesMetadata>
              </metadata></head>
              <body dur="0:05.000"><div begin="0.000" end="5.000">
                <p begin="1.000" end="2.500" ttm:agent="v1" itunes:key="L1"><span begin="1.000" end="1.700">Hello</span> <span begin="1.700" end="2.500">world</span></p>
                <p begin="2.500" end="5.000" ttm:agent="v1" itunes:key="L2"><span begin="2.500" end="3.500">Second</span> <span begin="3.500" end="5.000">line</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        /** AMLL's own head track: `<transliteration>` with no `xml:lang` at all. */
        val UNNAMED_TRANSLITERATION_DOCUMENT = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                <transliterations><transliteration>
                  <text for="L1">ki</text>
                </transliteration></transliterations>
              </iTunesMetadata></metadata></head>
              <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">き</span></p></div></body>
            </tt>
        """.trimIndent()
    }

    /**
     * The diagnostic reads the displayed document's own tag: it is what the app's
     * native parser keys the lane by, so a device log can compare it with the
     * model's advertised vector.
     */
    @Test
    fun `the head transliteration languages are read in order and de-duplicated`() {
        assertEquals(
            listOf("ja-Latn"),
            AppleLyricTtmlReader.transliterationLanguages(WORD_DOCUMENT),
        )
        val twoTracks = WORD_DOCUMENT
            .replace(
                "<transliterations>",
                "<transliterations>" +
                    "<transliteration xml:lang=\"ko-Latn\"><text for=\"L1\">k</text></transliteration>" +
                    "<transliteration xml:lang=\"ko-Latn\"><text for=\"L2\">k2</text></transliteration>",
            )
        assertEquals(
            listOf("ko-Latn", "ja-Latn"),
            AppleLyricTtmlReader.transliterationLanguages(twoTracks),
        )
        // An AMLL document that names no language reports none, which is exactly
        // the state the lane-name fix has to remove.
        assertEquals(
            emptyList<String>(),
            AppleLyricTtmlReader.transliterationLanguages(UNNAMED_TRANSLITERATION_DOCUMENT),
        )
        assertEquals(emptyList<String>(), AppleLyricTtmlReader.transliterationLanguages("not ttml"))
        assertEquals(emptyList<String>(), AppleLyricTtmlReader.transliterationLanguages(""))
    }
}
