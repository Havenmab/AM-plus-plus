package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricTtmlWriterTest {

    @Test
    fun `seconds formats decimal seconds like Apple ttml`() {
        assertEquals("0.000", AppleLyricTtmlWriter.seconds(0L))
        assertEquals("12.345", AppleLyricTtmlWriter.seconds(12_345L))
        assertEquals("62.003", AppleLyricTtmlWriter.seconds(62_003L))
    }

    @Test
    fun `duration formats minutes seconds millis`() {
        assertEquals("0:00.000", AppleLyricTtmlWriter.duration(0L))
        assertEquals("0:12.345", AppleLyricTtmlWriter.duration(12_345L))
        assertEquals("1:02.003", AppleLyricTtmlWriter.duration(62_003L))
        assertEquals("62:03.004", AppleLyricTtmlWriter.duration(3_723_004L))
    }

    @Test
    fun `build emits word spans when word timeline exists`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 12_000L,
                end = 14_000L,
                text = "第一句",
                words = listOf(
                    AppleTtmlWord(begin = 12_000L, end = 12_800L, text = "第一"),
                    AppleTtmlWord(begin = 12_800L, end = 13_900L, text = "句"),
                ),
            ),
        )
        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 20_000L)
        assertTrue(
            ttml.startsWith(
                "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
                    "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
                    "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
                    "itunes:timing=\"Word\" xml:lang=\"zh-Hans\" " +
                    "xml:space=\"preserve\">"
            )
        )
        assertTrue(ttml.contains("<ttm:agent type=\"person\" xml:id=\"v1\"/>"))
        assertTrue(ttml.contains("<body dur=\"0:20.000\">"))
        assertTrue(ttml.contains("<div begin=\"12.000\" end=\"14.000\">"))
        assertTrue(
            ttml.contains(
                "<p begin=\"12.000\" end=\"14.000\" " +
                    "ttm:agent=\"v1\" itunes:key=\"L1\">"
            )
        )
        assertTrue(
            ttml.contains(
                "<span begin=\"12.000\" end=\"12.800\">第一</span>" +
                    "<span begin=\"12.800\" end=\"13.900\">句</span>"
            )
        )
        assertTrue(TtmlInputPolicy.isAcceptable(ttml))
        assertTrue(TtmlTimingPolicy.isWord(ttml))
    }

    @Test
    fun `build preserves english spaces inside word spans`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 0L,
                end = 2_000L,
                text = "Hotel California",
                words = listOf(
                    AppleTtmlWord(0L, 800L, "Hotel "),
                    AppleTtmlWord(800L, 1_800L, "California"),
                ),
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 2_000L)

        assertTrue(ttml.contains(">Hotel </span>"))
        assertTrue(ttml.contains(">California</span>"))
        assertTrue(ttml.contains("xml:space=\"preserve\""))
    }

    @Test
    fun `build uses line timing and plain paragraph text without word timeline`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 1_000L,
                end = 3_000L,
                text = "无逐字行 & <特殊> \"字符\"",
                words = emptyList(),
            ),
        )
        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 5_000L)
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(
            ttml.contains(
                "<p begin=\"1.000\" end=\"3.000\" " +
                    "ttm:agent=\"v1\" itunes:key=\"L1\">" +
                    "无逐字行 &amp; &lt;特殊&gt; &quot;字符&quot;</p>"
            )
        )
        assertFalse(ttml.contains("<span"))
        assertTrue(TtmlInputPolicy.isAcceptable(ttml))
        assertFalse(TtmlTimingPolicy.isWord(ttml))
    }

    @Test
    fun `single full-line pseudo word still uses line timing`() {
        val text = "I've been up on the pedestal"
        val lines = listOf(
            AppleTtmlLine(
                begin = 1_000L,
                end = 4_000L,
                text = text,
                words = listOf(
                    AppleTtmlWord(
                        begin = 1_000L,
                        end = 4_000L,
                        text = text,
                    )
                ),
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 5_000L)

        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(ttml.contains(">$text</p>"))
        assertFalse(ttml.contains("<span"))
    }

    @Test
    fun `an lrc parsed result writes line timing`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = LrcParser.parseLrc("[00:01.000]hello\n[00:03.000]world"),
            translated = null,
            romanization = null,
        )

        val ttml = AppleLyricTtmlWriter.build(AppleLyricTtmlWriter.from(result), durationMs = 6_000L)

        assertFalse(TtmlTimingPolicy.isWord(ttml))
        assertTrue(ttml.contains("<p begin=\"1.000\" end=\"3.000\" ttm:agent=\"v1\" itunes:key=\"L1\">hello</p>"))
        assertTrue(ttml.contains("<p begin=\"3.000\" end=\"6.000\" ttm:agent=\"v1\" itunes:key=\"L2\">world</p>"))
    }

    @Test
    fun `a word timed source line writes word timing`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(
                    start = 0L,
                    end = 2_000L,
                    words = listOf(
                        LyricsWord(0L, 800L, "Hel"),
                        LyricsWord(800L, 1_900L, "lo"),
                    ),
                ),
            ),
            translated = null,
            romanization = null,
        )

        val ttml = AppleLyricTtmlWriter.build(AppleLyricTtmlWriter.from(result), durationMs = 2_000L)

        assertTrue(TtmlTimingPolicy.isWord(ttml))
        assertTrue(ttml.contains("<span begin=\"0.000\" end=\"0.800\">Hel</span>"))
        assertTrue(ttml.contains("<span begin=\"0.800\" end=\"1.900\">lo</span>"))
    }
}
