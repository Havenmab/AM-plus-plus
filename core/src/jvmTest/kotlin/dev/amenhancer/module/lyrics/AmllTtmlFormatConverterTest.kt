package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTtmlFormatConverterTest {

    /** The AMLL shape: empty namespace overrides plus inline auxiliary spans. */
    private val amllFormat = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:amll="http://www.example.com/ns/amll">
        <head><metadata xmlns=""><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body dur="00:04.000"><div xmlns="" begin="00:01.000" end="00:04.000">
        <p begin="00:01.000" end="00:02.000" ttm:agent="v1" itunes:key="L1">
        <span begin="00:01.000" end="00:01.500">aa</span> <span begin="00:01.500" end="00:02.000">bb</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
        <span ttm:role="x-roman">R1</span>
        </p>
        <p begin="00:03.000" end="00:04.000" ttm:agent="v1" itunes:key="L2">
        <span begin="00:03.000" end="00:04.000">cc</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T2</span>
        <span ttm:role="x-roman">R2</span>
        </p></div></body></tt>
    """.trimIndent()

    @Test
    fun `inline translation spans move into a head translations track`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.converted)
        assertTrue(
            result.ttml.contains(
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">",
            ),
        )
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text><text for=\"L2\">T2</text>" +
                    "</translation></translations>",
            ),
        )
    }

    @Test
    fun `inline romanization spans move into a head transliterations track`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"und-Latn\">" +
                    "<text for=\"L1\">R1</text><text for=\"L2\">R2</text>" +
                    "</transliteration></transliterations>",
            ),
        )
    }

    @Test
    fun `the body keeps only lyric spans`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val body = result.ttml.substringAfter("<body")

        assertFalse(body.contains("x-translation"))
        assertFalse(body.contains("x-roman"))
        assertTrue(body.contains("<span begin=\"00:01.000\" end=\"00:01.500\">aa</span>"))
        assertTrue(body.contains("<span begin=\"00:03.000\" end=\"00:04.000\">cc</span>"))
    }

    @Test
    fun `the root is marked word timed with the script inferred from the body`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val root = result.ttml.substringBefore('>')

        assertTrue(root.contains("""itunes:timing="Word""""))
        // No declared language and a Latin body: the neutral tag, never `ko`.
        assertTrue(root.contains("""xml:lang="und""""))
    }

    @Test
    fun `a document without timed spans is marked line timed`() {
        // An untimed lyric span, because a bare-text line now takes a timed span
        // of its own and is no longer a body without timed spans.
        val lineTimed = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span>a line</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(lineTimed)
        val root = result.ttml.substringBefore('>')

        assertTrue(root.contains("""itunes:timing="Line""""))
        assertTrue(root.contains("""xml:lang="und""""))
    }

    @Test
    fun `a preexisting timing or language declaration is replaced not duplicated`() {
        val declared = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Line" xml:lang="ja">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(declared)
        val root = result.ttml.substringBefore('>')

        assertEquals(1, Regex("""itunes:timing""").findAll(root).count())
        assertEquals(1, Regex("""xml:lang""").findAll(root).count())
        assertTrue(root.contains("""itunes:timing="Word""""))
        // The document's own declaration is the lyrics language, not the old
        // hardcoded `ko`.
        assertTrue(root.contains("""xml:lang="ja""""))
    }

    @Test
    fun `word timing inside an auxiliary span is carried over`() {
        val timedAux = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman"><span begin="0.0" end="0.5">ro</span><span begin="0.5" end="1.0">ma</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(timedAux)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\">" +
                    "<span begin=\"0.0\" end=\"0.5\">ro</span>" +
                    "<span begin=\"0.5\" end=\"1.0\">ma</span>" +
                    "</text>",
            ),
        )
    }

    @Test
    fun `a Japanese body names the root language and keeps the word slices`() {
        // The reported メタモリボン shape: no root xml:lang, kana lyrics and a
        // word-timed x-roman. The root must be `ja`, not the old hardcoded `ko`,
        // and the migrated head entry must keep its per-word spans and timings.
        val japanese = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="0.5">何</span><span begin="0.5" end="1.0">し</span>
            <span ttm:role="x-roman"><span begin="0.0" end="0.5">nani</span><span begin="0.5" end="1.0">shi</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(japanese)
        val root = result.ttml.substringBefore('>')

        assertTrue(root.contains("""xml:lang="ja""""))
        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"ja-Latn\">" +
                    "<text for=\"L1\"><span begin=\"0.0\" end=\"0.5\">nani</span>" +
                    "<span begin=\"0.5\" end=\"1.0\">shi</span></text>" +
                    "</transliteration></transliterations>",
            ),
        )
    }

    @Test
    fun `background auxiliary spans become x-bg spans inside the text`() {
        val withBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">BT1</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(withBackground)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\">T1<span ttm:role=\"x-bg\">(BT1)</span></text>",
            ),
        )
        // The background vocal itself stays in the body, without its translation.
        val body = result.ttml.substringAfter("<body")
        assertTrue(body.contains("<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>"))
        assertFalse(body.contains("BT1"))
    }

    @Test
    fun `a background translation already parenthesized is not wrapped twice`() {
        val parenthesized = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">（已加括号）</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(parenthesized)

        assertTrue(
            result.ttml.contains("<span ttm:role=\"x-bg\">（已加括号）</span></text>"),
        )
    }

    @Test
    fun `a background vocal loses the timing apple reads from its syllables`() {
        val timedBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="2.0">(bb)</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(timedBackground)
        val body = result.ttml.substringAfter("<body")

        assertTrue(
            body.contains("<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>"),
        )
    }

    @Test
    fun `a background vocal starting before the lyrics moves to the front of the line`() {
        val leadingBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="1.0" end="3.0" itunes:key="L1">
            <span begin="2.0" end="3.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(leadingBackground)
        val body = result.ttml.substringAfter("<body")

        assertTrue(
            body.contains(
                "itunes:key=\"L1\">" +
                    "<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>",
            ),
        )
        assertTrue(body.indexOf("x-bg") < body.indexOf(">aa<"))
        assertEquals(1, Regex("""x-bg""").findAll(body).count())
    }

    @Test
    fun `a line translated only in its background opens with the placeholder`() {
        val backgroundOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L9">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(backgroundOnly)

        // The space stands in for the main translation the line never had, so
        // Apple does not read the background one as belonging to the line.
        assertTrue(
            result.ttml.contains("<text for=\"L9\"> <span ttm:role=\"x-bg\">(BT)</span></text>"),
        )
    }

    @Test
    fun `a line the source left untranslated still holds its place in the track`() {
        val gapped = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p>
            <p begin="2.0" end="3.0" itunes:key="L3">
            <span begin="2.0" end="3.0">cc</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T3</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(gapped)

        // Without the L2 placeholder Android Apple Music reads T3 onto L2 and
        // gives up on the lyrics entirely.
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text>" +
                    "<text for=\"L2\"> </text>" +
                    "<text for=\"L3\">T3</text>" +
                    "</translation></translations>",
            ),
        )
    }

    @Test
    fun `an empty translation span counts as no translation`() {
        val blank = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN"></span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T2</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(blank)

        assertTrue(
            result.ttml.contains("<text for=\"L1\"> </text><text for=\"L2\">T2</text>"),
        )
        // The emptied span leaves the body all the same.
        assertFalse(result.ttml.substringAfter("<body").contains("x-translation"))
    }

    @Test
    fun `a lyric with no translation at all gets no translations track`() {
        val romanOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(romanOnly)

        assertFalse(result.ttml.contains("<translations>"))
        // The kind that was spoken for still lists every line.
        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"und-Latn\">" +
                    "<text for=\"L1\">R1</text><text for=\"L2\"> </text>" +
                    "</transliteration></transliterations>",
            ),
        )
    }

    @Test
    fun `a background translation alone still fills the track for every line`() {
        val backgroundOnlyLine = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p>
            <p begin="2.0" end="3.0" itunes:key="L2">
            <span begin="2.0" end="3.0">cc</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(backgroundOnlyLine)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\"> <span ttm:role=\"x-bg\">(BT)</span></text>" +
                    "<text for=\"L2\"> </text>",
            ),
        )
    }

    @Test
    fun `the first auxiliary span of each kind wins per line`() {
        val several = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">ZH1</span>
            <span ttm:role="x-translation" xml:lang="en">EN1</span>
            <span ttm:role="x-roman">ROMA1</span>
            <span ttm:role="x-roman">ROMA2</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(several)

        // Matching each whole track proves exactly one text survived per kind;
        // `<text for="L1">` itself appears twice, once per track.
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">ZH1</text>" +
                    "</translation></translations>",
            ),
        )
        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"und-Latn\">" +
                    "<text for=\"L1\">ROMA1</text>" +
                    "</transliteration></transliterations>",
            ),
        )
        assertFalse(result.ttml.contains("EN1"))
        assertFalse(result.ttml.contains("ROMA2"))
    }

    @Test
    fun `every romanization of a background group is kept in order`() {
        // A background vocal whose reading is split across several `x-roman`
        // spans, one per word. The old code kept only the first and dropped the
        // rest; every span must now contribute, concatenated in document order.
        val splitBackgroundRoman = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-roman"><span begin="1.0" end="1.5">bei</span></span>
            <span ttm:role="x-roman"><span begin="1.5" end="2.0">jing</span></span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(splitBackgroundRoman)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\"> <span ttm:role=\"x-bg\">" +
                    "(<span begin=\"1.0\" end=\"1.5\">bei</span>" +
                    "<span begin=\"1.5\" end=\"2.0\">jing</span>)" +
                    "</span></text>",
            ),
        )
        // Both word slices survive, and neither copy is left in the body.
        assertTrue(result.ttml.contains(">bei<"))
        assertTrue(result.ttml.contains(">jing<"))
        assertFalse(result.ttml.substringAfter("<body").contains("x-roman"))
    }

    @Test
    fun `a single background romanization is carried over unchanged`() {
        val singleBackgroundRoman = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-roman">beijing</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(singleBackgroundRoman)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\"> <span ttm:role=\"x-bg\">(beijing)</span></text>",
            ),
        )
        assertFalse(result.ttml.substringAfter("<body").contains("x-roman"))
    }

    @Test
    fun `a bare text line gets one span spanning the whole line`() {
        val legacyLines = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="00:23.038" end="00:24.843" ttm:agent="v1" itunes:key="L7">Yeah</p>
            <p begin="00:25.000" end="00:26.000" itunes:key="L8"><span begin="00:25.000" end="00:26.000">Whoa</span></p>
            </div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(legacyLines)

        assertTrue(result.converted)
        // The word takes the line's own extent: that is what the per-word
        // matcher aligns a lane against.
        assertTrue(
            result.ttml.contains(
                "<p begin=\"00:23.038\" end=\"00:24.843\" ttm:agent=\"v1\" itunes:key=\"L7\">" +
                    "<span begin=\"00:23.038\" end=\"00:24.843\">Yeah</span></p>",
            ),
        )
        // A line that already carries a span is untouched.
        assertTrue(
            result.ttml.contains(
                "<p begin=\"00:25.000\" end=\"00:26.000\" itunes:key=\"L8\">" +
                    "<span begin=\"00:25.000\" end=\"00:26.000\">Whoa</span></p>",
            ),
        )
    }

    @Test
    fun `a single layer background vocal gains an untimed outer span`() {
        val singleLayer = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="2:32.000" end="2:34.000" itunes:key="L1">
            <span begin="2:32.000" end="2:32.296">aa</span>
            <span ttm:role="x-bg" begin="2:32.296" end="2:33.666">(Ooh)</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(singleLayer)
        val body = result.ttml.substringAfter("<body")

        // Outer span: role only. Inner span: the timing and the text.
        assertTrue(
            body.contains(
                "<span ttm:role=\"x-bg\"><span begin=\"2:32.296\" end=\"2:33.666\">(Ooh)</span></span>",
            ),
        )
        assertFalse(body.contains("<span ttm:role=\"x-bg\" begin="))
        assertFalse(body.contains("<span ttm:role=\"x-bg\" end="))
    }

    @Test
    fun `a two layer background vocal loses the timing on its outer span`() {
        val twoLayer = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="1.5">(Al</span><span begin="1.5" end="2.0">right)</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(twoLayer)
        val body = result.ttml.substringAfter("<body")

        // The inner syllables keep their timing; the redundant outer copy goes.
        assertTrue(
            body.contains(
                "<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"1.5\">(Al</span>" +
                    "<span begin=\"1.5\" end=\"2.0\">right)</span></span>",
            ),
        )
        assertFalse(body.contains("<span ttm:role=\"x-bg\" begin="))
    }

    @Test
    fun `the background wrapper keeps a timing it has nowhere to move`() {
        // Nothing to wrap, so the outer timing is the group's only extent and
        // stays as the fallback rather than being dropped.
        val emptyBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="2.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0">   </span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(emptyBackground)

        assertTrue(
            result.ttml.contains("<span ttm:role=\"x-bg\" begin=\"1.0\" end=\"2.0\">"),
        )
    }

    @Test
    fun `the legacy background shape is normalized idempotently`() {
        val legacy = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="2.0" itunes:key="L1">Yeah</p>
            <p begin="2.0" end="4.0" itunes:key="L2">
            <span begin="2.0" end="3.0">aa</span>
            <span ttm:role="x-bg" begin="3.0" end="3.5">(Hey!)</span>
            <span ttm:role="x-bg" begin="3.5" end="4.0"><span begin="3.5" end="3.75">(Ooh</span><span begin="3.75" end="4.0">!)</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val once = AmllTtmlFormatConverter.toAppleFormat(legacy)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertTrue(once.converted)
        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
        assertTrue(once.ttml.contains("<span begin=\"0.0\" end=\"2.0\">Yeah</span>"))
        assertTrue(
            once.ttml.contains(
                "<span ttm:role=\"x-bg\"><span begin=\"3.0\" end=\"3.5\">(Hey!)</span></span>",
            ),
        )
        assertTrue(
            once.ttml.contains(
                "<span ttm:role=\"x-bg\"><span begin=\"3.5\" end=\"3.75\">(Ooh</span>" +
                    "<span begin=\"3.75\" end=\"4.0\">!)</span></span>",
            ),
        )
        // Never a second wrapper.
        assertFalse(once.ttml.contains("<span ttm:role=\"x-bg\"><span ttm:role=\"x-bg\">"))
        assertFalse(once.ttml.substringAfter("<body").contains("<span ttm:role=\"x-bg\" begin="))
    }

    @Test
    fun `a line left bare by its migrated translation is wrapped too`() {
        // The source hid the lyric beside the auxiliary span, so the first run
        // must already emit the wrapped shape rather than leaving it to a second.
        val hiddenLyric = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1"><span ttm:role="x-translation" xml:lang="zh-CN">T1</span>Yeah</p></div></body></tt>
        """.trimIndent()

        val once = AmllTtmlFormatConverter.toAppleFormat(hiddenLyric)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertTrue(once.converted)
        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
        assertTrue(
            once.ttml.contains(
                "<p begin=\"0.0\" end=\"1.0\" itunes:key=\"L1\">" +
                    "<span begin=\"0.0\" end=\"1.0\">Yeah</span></p>",
            ),
        )
    }

    @Test
    fun `a legacy background span in a declared head lane is normalized too`() {
        val headLane = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">T1<span ttm:role="x-bg" begin="0.5" end="1.0">(BT)</span></text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(headLane)

        assertTrue(result.converted)
        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\">T1<span ttm:role=\"x-bg\">" +
                    "<span begin=\"0.5\" end=\"1.0\">(BT)</span></span></text>",
            ),
        )
        // A converter-written lane entry stays canonical and untouched.
        val canonical = AmllTtmlFormatConverter.toAppleFormat(
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">T1<span ttm:role="x-bg">(BT)</span></text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
            """.trimIndent(),
        )
        assertFalse(canonical.converted)
    }

    @Test
    fun `a declared translation lane keeps its text through both passes`() {
        // The injection-path shape: a document already carries a translations
        // lane in the head while the body still has an undeclared romanization,
        // so migration runs and the post-migration pass runs beside that lane.
        // The lane's `<text for=…>` bodies are read verbatim by the app and must
        // never be given a span.
        val declaredLane = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">译文一</text><text for="L2"><span ttm:role="x-bg">(合)</span></text><text for="L3">译文三</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span></p>
            <p begin="1.0" end="2.0" itunes:key="L2"><span ttm:role="x-roman">R2</span>hidden</p>
            <p begin="2.0" end="3.0" itunes:key="L3">bare</p>
            </div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(declaredLane)
        val head = result.ttml.substringBefore("<body")

        // The whole declared translations block is byte-for-byte what went in.
        assertTrue(
            head.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">译文一</text>" +
                    "<text for=\"L2\"><span ttm:role=\"x-bg\">(合)</span></text>" +
                    "<text for=\"L3\">译文三</text>" +
                    "</translation></translations>",
            ),
        )
        // No `<text>` body was wrapped, not even the bare ones.
        assertFalse(head.contains("<text for=\"L1\"><span"))
        assertFalse(head.contains("<text for=\"L3\"><span"))
        assertTrue(head.contains("<text for=\"L1\">译文一</text>"))
        assertTrue(head.contains("<text for=\"L3\">译文三</text>"))
        // The undeclared romanization still migrates into its own lane, and the
        // body lines the migration exposed are wrapped as body `<p>` rules say.
        assertTrue(head.contains("<transliterations><transliteration xml:lang=\"und-Latn\">"))
        assertTrue(
            result.ttml.contains(
                "<p begin=\"1.0\" end=\"2.0\" itunes:key=\"L2\">" +
                    "<span begin=\"1.0\" end=\"2.0\">hidden</span></p>",
            ),
        )
        assertTrue(
            result.ttml.contains(
                "<p begin=\"2.0\" end=\"3.0\" itunes:key=\"L3\">" +
                    "<span begin=\"2.0\" end=\"3.0\">bare</span></p>",
            ),
        )

        // Both passes are idempotent beside the declared lane.
        val twice = AmllTtmlFormatConverter.toAppleFormat(result.ttml)
        assertFalse(twice.converted)
        assertEquals(result.ttml, twice.ttml)
    }

    @Test
    fun `empty default namespace overrides are dropped`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertFalse(result.ttml.contains("xmlns=\"\""))
        assertTrue(result.ttml.contains("<metadata>"))
        assertTrue(result.ttml.contains("<div begin=\"00:01.000\""))
        assertTrue(result.ttml.contains("xmlns=\"http://www.w3.org/ns/ttml\""))
    }

    @Test
    fun `whitespace between lyric spans is preserved`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.ttml.contains("</span> <span begin=\"00:01.500\""))
    }

    @Test
    fun `a document already declaring auxiliary tracks is left alone`() {
        val appleFormat = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation xml:lang="zh-CN"><text for="L1">T1</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(appleFormat)

        assertFalse(result.converted)
        assertEquals(appleFormat, result.ttml)
    }

    /** An Apple transliteration in the head, translations still inline as AMLL. */
    private val mixedFormat = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
        <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text><text for="L2"><span begin="1.0" end="2.0">ma</span></text></transliteration></transliterations>
        </iTunesMetadata></metadata></head>
        <body><div>
        <p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T1</span></p>
        <p begin="1.0" end="2.0" itunes:key="L2"><span begin="1.0" end="2.0">bb</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T2</span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun `inline translations still migrate beside a declared transliteration track`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(mixedFormat)

        assertTrue(result.converted)
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text><text for=\"L2\">T2</text>" +
                    "</translation></translations>",
            ),
        )
        assertFalse(result.ttml.substringAfter("<body").contains("x-translation"))
        // No declared language and a Latin body: the neutral root tag.
        assertTrue(result.ttml.substringBefore('>').contains("""xml:lang="und""""))
    }

    @Test
    fun `the declared transliteration track is neither duplicated nor rewritten`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(mixedFormat)

        assertEquals(1, Regex("""<iTunesMetadata""").findAll(result.ttml).count())
        assertEquals(1, Regex("""<transliterations>""").findAll(result.ttml).count())
        // Apple lists translations first, and the track it already had is intact.
        assertTrue(result.ttml.indexOf("<translations>") < result.ttml.indexOf("<transliterations>"))
        // The head entry is kept, and an unnamed one is stamped with the
        // per-song language tag so the app can key its pronunciation lane.
        assertTrue(
            Regex(
                "<transliterations><transliteration xml:lang=\"[a-z]{2,3}-Latn\">" +
                    "<text for=\"L1\"><span begin=\"0.0\" end=\"1.0\">ro</span></text>",
            ).containsMatchIn(result.ttml),
        )
        assertTrue(TtmlInputPolicy.isAcceptable(result.ttml))
    }

    @Test
    fun `a declared kind keeps its inline spans rather than migrating twice`() {
        val declaredAndInline = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1">ro</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            <span ttm:role="x-translation">T1</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(declaredAndInline)

        assertEquals(1, Regex("""<transliterations>""").findAll(result.ttml).count())
        assertFalse(result.ttml.contains("<text for=\"L1\">R1</text>"))
        // The native parser has no `x-roman` role, so the redundant body copy is
        // dropped rather than left in the line as an untimed lyric span.
        assertFalse(result.ttml.substringAfter("<body").contains("x-roman"))
        assertTrue(result.ttml.contains("<text for=\"L1\">T1</text>"))
    }

    @Test
    fun `the mixed rewrite is idempotent`() {
        val once = AmllTtmlFormatConverter.toAppleFormat(mixedFormat)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
    }

    @Test
    fun `an itunes metadata carrying only songwriters is still migrated into`() {
        // AMLL writes this element itself, so it is no sign of the Apple format.
        val songwritersOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <songwriters><songwriter>Someone</songwriter></songwriters>
            </iTunesMetadata></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(songwritersOnly)

        assertTrue(result.converted)
        // One container, with the tracks ahead of the songwriters Apple lists last.
        assertEquals(1, Regex("""<iTunesMetadata""").findAll(result.ttml).count())
        assertTrue(
            result.ttml.contains(
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
                    "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text></translation></translations>",
            ),
        )
        assertTrue(result.ttml.indexOf("<translations>") < result.ttml.indexOf("<songwriters>"))
    }

    @Test
    fun `lines without a key keep their auxiliary spans rather than losing them`() {
        val noKey = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(noKey)

        assertFalse(result.ttml.contains("<iTunesMetadata"))
        assertTrue(result.ttml.contains("<span ttm:role=\"x-translation\" xml:lang=\"zh-CN\">T1</span>"))
    }

    @Test
    fun `a document with no auxiliary spans still gets the root attributes`() {
        val plain = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(plain)

        assertTrue(result.converted)
        assertFalse(result.ttml.contains("<iTunesMetadata"))
        assertFalse(result.ttml.contains("xmlns=\"\""))
        assertTrue(result.ttml.substringBefore('>').contains("""itunes:timing="Word""""))
        assertTrue(result.ttml.substringBefore('>').contains("""xml:lang="und""""))
    }

    @Test
    fun `text nodes comments and cdata are copied verbatim`() {
        val tricky = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- xmlns="" in a comment -->
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><amll:meta key="musicName" value="a &gt; b xmlns=&quot;&quot;"/></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">2 &lt; 3</span>
            <span ttm:role="x-translation">&lt;kept&gt;</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(tricky)

        assertTrue(result.ttml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(result.ttml.contains("<!-- xmlns=\"\" in a comment -->"))
        assertTrue(result.ttml.contains("value=\"a &gt; b xmlns=&quot;&quot;\""))
        assertTrue(result.ttml.contains(">2 &lt; 3<"))
        assertTrue(result.ttml.contains("<text for=\"L1\">&lt;kept&gt;</text>"))
    }

    @Test
    fun `the rewrite is idempotent`() {
        val once = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
    }

    @Test
    fun `malformed and empty input never throws`() {
        assertEquals("", AmllTtmlFormatConverter.toAppleFormat("").ttml)
        assertEquals("plain text", AmllTtmlFormatConverter.toAppleFormat("plain text").ttml)
        assertFalse(AmllTtmlFormatConverter.toAppleFormat("<unclosed attr=\"").converted)
        // A truncated document still has its root declared, but no track is
        // invented from lines the scanner could not close.
        listOf(
            "<tt><body>",
            "<tt><head><metadata/></head><body><p itunes:key=\"L1\">",
        ).forEach { malformed ->
            val result = AmllTtmlFormatConverter.toAppleFormat(malformed)

            assertFalse(result.ttml.contains("<iTunesMetadata"))
            assertTrue(result.ttml.startsWith("<tt "))
            assertTrue(result.ttml.contains("""xml:lang="und""""))
        }
    }

    @Test
    fun `the converted document still satisfies the ttml input policy`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(TtmlInputPolicy.isAcceptable(result.ttml))
    }

    @Test
    fun `a single quoted itunes namespace declaration is retained without duplication`() {
        val singleQuotedNamespace = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes='http://music.apple.com/lyric-ttml-internal'>
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(singleQuotedNamespace)
        val root = result.ttml.substringBefore('>')

        assertTrue(result.converted)
        assertEquals(1, Regex("""xmlns:itunes\s*=""").findAll(root).count())
        assertTrue(root.contains("xmlns:itunes='http://music.apple.com/lyric-ttml-internal'"))
    }

    @Test
    fun `a body syllable with a single quoted begin sets word timing`() {
        val singleQuotedBegin = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin='0.0' end='1.0'>aa</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(singleQuotedBegin)
        val root = result.ttml.substringBefore('>')

        assertTrue(result.converted)
        assertTrue(root.contains("itunes:timing=\"Word\""))
    }

    @Test
    fun `seconds suffix background timing is stripped and an early background moves first`() {
        val secondsBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0s" end="2s" itunes:key="L1">
            <span begin="1s" end="2s">aa</span>
            <span ttm:role="x-bg" begin="0.5s" end="1s"><span begin="0.5s" end="1s">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(secondsBackground)
        val body = result.ttml.substringAfter("<body")

        assertTrue(body.contains("<span ttm:role=\"x-bg\"><span begin=\"0.5s\" end=\"1s\">(bb)</span></span>"))
        assertTrue(body.indexOf("x-bg") < body.indexOf(">aa<"))
        assertFalse(body.contains("<span ttm:role=\"x-bg\" begin=\"0.5s\" end=\"1s\">"))
        assertEquals(1, Regex("""x-bg""").findAll(body).count())
    }

    @Test
    fun `timed spans only in a migrated head track keep a line timed body`() {
        // The body's lyric is an untimed span: a bare-text line would now take a
        // timed span of its own, which is exactly what this test must not have.
        val timedHeadLineBody = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span>aa</span> <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(timedHeadLineBody)
        val root = result.ttml.substringBefore('>')

        assertTrue(result.converted)
        assertTrue(root.contains("itunes:timing=\"Line\""))
    }

    @Test
    fun `existing translations stay before migrated inline romanization`() {
        val translationsHeadRomanBody = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">T1</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(translationsHeadRomanBody)

        assertTrue(result.converted)
        assertTrue(result.ttml.indexOf("<translations>") < result.ttml.indexOf("<transliterations>"))
        assertTrue(result.ttml.contains("<text for=\"L1\">R1</text>"))
    }

    /**
     * The device shape: AMLL declares the head `<transliterations>` itself but
     * writes its entry with no `xml:lang` at all. The native parser then parses
     * the romanization under no language and `getPronunciationLanguages()` stays
     * empty, so nothing can select the lane. The per-song tag has to be stamped
     * onto the preserved entry.
     */
    @Test
    fun `an unnamed head transliteration is stamped with the song's own language tag`() {
        val unnamedHead = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1">ki</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">き</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            <span ttm:role="x-roman">ki</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(unnamedHead)

        assertTrue(result.converted)
        assertTrue(result.ttml.contains("<transliteration xml:lang=\"ja-Latn\"><text for=\"L1\">ki</text>"))
        // The declared track wins over the duplicate inline lane, and the native
        // parser does not know the inline role, so the body copy is dropped.
        assertFalse(result.ttml.substringAfter("<body").contains("x-roman"))
    }

    @Test
    fun `an explicit lyric language overrides the script the lyrics are written in`() {
        val hangulBody = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">안녕</span>
            <span ttm:role="x-roman">annyeong</span>
            </p></div></body></tt>
        """.trimIndent()

        assertTrue(
            AmllTtmlFormatConverter.toAppleFormat(hangulBody).ttml
                .contains("<transliteration xml:lang=\"ko-Latn\">"),
        )
        assertTrue(
            AmllTtmlFormatConverter.toAppleFormat(hangulBody, lyricLanguage = "zh").ttml
                .contains("<transliteration xml:lang=\"zh-Latn\">"),
        )
    }

    @Test
    fun `a declared lyric language names the romanization tag per song`() {
        fun converted(language: String) = AmllTtmlFormatConverter.toAppleFormat(
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="$language">
            <head><metadata xmlns=""/></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p></div></body></tt>
            """.trimIndent(),
        ).ttml

        assertTrue(converted("ja").contains("<transliteration xml:lang=\"ja-Latn\">"))
        assertTrue(converted("ja-JP").contains("<transliteration xml:lang=\"ja-Latn\">"))
        assertTrue(converted("ko-KR").contains("<transliteration xml:lang=\"ko-Latn\">"))
        assertTrue(converted("zh-Hant").contains("<transliteration xml:lang=\"zh-Latn\">"))
        // ISO 639-2 and the Chinese variety subtags name the same lane.
        assertTrue(converted("jpn").contains("<transliteration xml:lang=\"ja-Latn\">"))
        assertTrue(converted("cmn").contains("<transliteration xml:lang=\"zh-Latn\">"))
        assertTrue(converted("yue").contains("<transliteration xml:lang=\"zh-Latn\">"))
    }

    @Test
    fun `a song whose lyrics language cannot be determined keeps the neutral tag`() {
        val latinLyrics = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(latinLyrics)

        assertTrue(result.ttml.contains("<transliteration xml:lang=\"und-Latn\">"))
    }

    @Test
    fun `a named head transliteration survives a migration beside it untouched`() {
        val named = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration xml:lang="en-Latn"><text for="L1">R1</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(named)

        assertTrue(result.converted)
        assertTrue(result.ttml.contains("<transliteration xml:lang=\"en-Latn\">"))
        assertFalse(result.ttml.contains("ja-Latn"))
        assertFalse(result.ttml.contains("und-Latn"))
    }

    @Test
    fun `a head translation never names the lyrics`() {
        val translatedHead = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">中文</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(translatedHead)

        assertTrue(result.ttml.contains("<transliteration xml:lang=\"und-Latn\">"))
    }
}
