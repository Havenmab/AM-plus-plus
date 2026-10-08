package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricTtmlWriterTranslationTest {

    @Test
    fun `a translation free document is byte identical to the legacy writer output`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 0L,
                end = 1_000L,
                text = "hello",
                words = listOf(
                    AppleTtmlWord(0L, 500L, "hel"),
                    AppleTtmlWord(500L, 900L, "lo"),
                ),
            ),
            AppleTtmlLine(
                begin = 1_000L,
                end = 2_000L,
                text = "world",
                words = listOf(AppleTtmlWord(1_000L, 1_800L, "world")),
            ),
        )

        val expected =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
                "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
                "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
                "itunes:timing=\"Word\" xml:lang=\"zh-Hans\" xml:space=\"preserve\">" +
                "<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/>" +
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\"/>" +
                "</metadata></head>" +
                "<body dur=\"0:02.000\">" +
                "<div begin=\"0.000\" end=\"2.000\">" +
                "<p begin=\"0.000\" end=\"1.000\" ttm:agent=\"v1\" itunes:key=\"L1\">" +
                "<span begin=\"0.000\" end=\"0.500\">hel</span>" +
                "<span begin=\"0.500\" end=\"0.900\">lo</span></p>" +
                "<p begin=\"1.000\" end=\"2.000\" ttm:agent=\"v1\" itunes:key=\"L2\">" +
                "<span begin=\"1.000\" end=\"1.800\">world</span></p>" +
                "</div></body></tt>"

        assertEquals(expected, AppleLyricTtmlWriter.build(lines, durationMs = 2_000L))
    }

    @Test
    fun `translation and romanization lanes are emitted as head tracks with contiguous keys`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 0L,
                end = 1_000L,
                text = "hello",
                words = listOf(
                    AppleTtmlWord(0L, 500L, "hel"),
                    AppleTtmlWord(500L, 900L, "lo"),
                ),
                translation = "你好",
                romanization = "ni hao",
            ),
            AppleTtmlLine(
                begin = 1_000L,
                end = 2_000L,
                text = "world",
                words = listOf(AppleTtmlWord(1_000L, 1_800L, "world")),
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 2_000L)

        assertTrue(
            ttml.contains(
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
                    "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">你好</text><text for=\"L2\"> </text>" +
                    "</translation></translations>" +
                    "<transliterations><transliteration xml:lang=\"und-Latn\">" +
                    "<text for=\"L1\">ni hao</text><text for=\"L2\"> </text>" +
                    "</transliteration></transliterations>" +
                    "</iTunesMetadata>",
            ),
        )
        // Still a Word-timed document with its body spans intact.
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0.000\" end=\"0.500\">hel</span>"))
    }

    @Test
    fun `an untranslated line holds the documented single space entry`() {
        val lines = (1..3).map { index ->
            AppleTtmlLine(
                begin = index * 1_000L,
                end = index * 1_000L + 900L,
                text = "line$index",
                words = emptyList(),
                translation = if (index == 2) null else "译$index",
            )
        }

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 3_000L)

        assertTrue(
            ttml.contains(
                "<text for=\"L1\">译1</text>" +
                    "<text for=\"L2\"> </text>" +
                    "<text for=\"L3\">译3</text>",
            ),
        )
    }

    @Test
    fun `a background translation rides an x-bg span after the absent main placeholder`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 0L,
                end = 1_000L,
                text = "Ooo, don't you know",
                words = emptyList(),
                backgroundTranslation = "Don't you know?",
            ),
            AppleTtmlLine(
                begin = 1_000L,
                end = 2_000L,
                text = "Second",
                words = emptyList(),
                translation = "第二句",
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 2_000L)

        assertTrue(
            ttml.contains(
                "<text for=\"L1\"> <span ttm:role=\"x-bg\">(Don't you know?)</span></text>" +
                    "<text for=\"L2\">第二句</text>",
            ),
        )
    }

    @Test
    fun `a lane no line contributed to is not opened`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 0L,
                end = 1_000L,
                text = "hello",
                words = emptyList(),
                translation = "你好",
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 1_000L)

        assertTrue(ttml.contains("<translations>"))
        assertFalse(ttml.contains("<transliterations>"))
    }

    @Test
    fun `slash only translation placeholders do not open a track`() {
        val lines = listOf(
            AppleTtmlLine(
                begin = 0L,
                end = 1_000L,
                text = "Listen",
                words = emptyList(),
                translation = "// //",
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(lines, durationMs = 1_000L)

        assertFalse(ttml.contains("<translations>"))
        assertTrue(ttml.contains("<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\"/>"))
    }

    @Test
    fun `from a provider result carries the extracted translation and romanization lanes`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(
                    start = 1_000L,
                    end = 2_000L,
                    words = listOf(LyricsWord(1_000L, 2_000L, "hello")),
                ),
            ),
            translated = listOf(
                LyricsLine(
                    start = 1_000L,
                    end = 2_000L,
                    words = listOf(LyricsWord(1_000L, 2_000L, "你好")),
                ),
            ),
            romanization = listOf(
                LyricsLine(
                    start = 1_000L,
                    end = 2_000L,
                    words = listOf(LyricsWord(1_000L, 2_000L, "ni hao")),
                ),
            ),
        )

        val ttml = AppleLyricTtmlWriter.build(
            AppleLyricTtmlWriter.from(result),
            durationMs = 2_000L,
        )

        assertTrue(ttml.contains("<text for=\"L1\">你好</text>"))
        assertTrue(ttml.contains("<text for=\"L1\">ni hao</text>"))
    }
}
