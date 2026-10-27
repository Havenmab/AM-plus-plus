package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车道纠正在两条真实入口上的落地：抓取（[OnlineTranslationExtraction]）与合并发布
 * （[OnlineTranslationEnrichment]）。
 *
 * 修的是同一个事故：QQ 音乐对日语歌的 `trans` 是读音（`[kana:…]` + 罗马音），旧代码
 * 把它当译文发布，于是页面把罗马音显示成翻译，重启后持久层又按「译文」车道复现。
 */
class OnlineTranslationLaneCorrectionTest {

    @Test
    fun `a qq japanese trans that is a reading becomes the romanization lane`() {
        val extracted = OnlineTranslationExtraction.extract(
            LyricsResult(
                tags = emptyMap(),
                original = listOf(line(1_000L, 2_000L, "夢ならば")),
                translated = listOf(line(1_000L, 2_000L, "[kana:ゆめならば] yume naraba")),
                romanization = null,
            ),
        ).single()

        assertNull("a reading must never be extracted as a translation", extracted.translation)
        assertEquals("yume naraba", extracted.romanization)
    }

    @Test
    fun `a netease chinese tlyric stays in the translation lane`() {
        val extracted = OnlineTranslationExtraction.extract(
            LyricsResult(
                tags = emptyMap(),
                original = listOf(line(1_000L, 2_000L, "夢ならば")),
                translated = listOf(line(1_000L, 2_000L, "如果这一切都是梦境该有多好")),
                romanization = listOf(line(1_000L, 2_000L, "yume naraba")),
            ),
        ).single()

        assertEquals("如果这一切都是梦境该有多好", extracted.translation)
        assertEquals("yume naraba", extracted.romanization)
    }

    @Test
    fun `a chinese roma column is extracted as the translation`() {
        val extracted = OnlineTranslationExtraction.extract(
            LyricsResult(
                tags = emptyMap(),
                original = listOf(line(1_000L, 2_000L, "夢ならば")),
                translated = null,
                romanization = listOf(line(1_000L, 2_000L, "如果这一切都是梦境")),
            ),
        ).single()

        assertEquals("如果这一切都是梦境", extracted.translation)
        assertNull("a translation must never stay in the romanization lane", extracted.romanization)
    }

    @Test
    fun `the same romaji in trans and roma is kept once as the romanization`() {
        val extracted = OnlineTranslationExtraction.extract(
            LyricsResult(
                tags = emptyMap(),
                original = listOf(line(1_000L, 2_000L, "夢ならば")),
                translated = listOf(line(1_000L, 2_000L, "yume naraba")),
                romanization = listOf(line(1_000L, 2_000L, "yume naraba")),
            ),
        ).single()

        assertNull(extracted.translation)
        assertEquals("yume naraba", extracted.romanization)
    }

    @Test
    fun `an english original never contributes a translation`() {
        // 同一条英文被当成译文返回时不该发布；英文歌也没有发音车道。
        val extracted = OnlineTranslationExtraction.extract(
            LyricsResult(
                tags = emptyMap(),
                original = listOf(line(1_000L, 2_500L, "Hello world")),
                translated = listOf(line(1_000L, 2_500L, "Hello world")),
                romanization = listOf(line(1_000L, 2_500L, "Haro warudo")),
            ),
        ).single()

        assertNull(extracted.translation)
        assertFalse(LyricLaneClassifier.isRomanizationLike(extracted.romanization, "Hello world"))
    }

    @Test
    fun `a candidate that is only a reading publishes nothing`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.QM,
                    lines = OnlineTranslationExtraction.extract(
                        LyricsResult(
                            tags = emptyMap(),
                            original = listOf(
                                line(1_000L, 2_500L, "夢ならば"),
                                line(2_500L, 5_000L, "ありがとう"),
                            ),
                            translated = listOf(
                                line(1_000L, 2_500L, "[kana:ゆめならば] yume naraba"),
                                line(2_500L, 5_000L, "[kana:ありがとう] arigatou"),
                            ),
                            romanization = null,
                        ),
                    ),
                ),
            ),
            durationMs = 5_000L,
        )

        assertNull("a reading is not a translation, so there is nothing to publish", outcome)
    }

    @Test
    fun `the chinese half of a mixed candidate publishes and the reading half does not`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.QM,
                    lines = OnlineTranslationExtraction.extract(
                        LyricsResult(
                            tags = emptyMap(),
                            original = listOf(
                                line(1_000L, 2_500L, "夢ならば"),
                                line(2_500L, 5_000L, "ありがとう"),
                            ),
                            // QQ 的 trans 常常一半是真译文、一半是读音。
                            translated = listOf(
                                line(1_000L, 2_500L, "如果这一切都是梦境"),
                                line(2_500L, 5_000L, "[kana:ありがとう] arigatou"),
                            ),
                            romanization = null,
                        ),
                    ),
                ),
            ),
            durationMs = 5_000L,
        )

        assertNotNull("the Chinese line still has to be published", outcome)
        assertEquals("如果这一切都是梦境", outcome!!.lines[0].translation)
        assertNull(outcome.lines[1].translation)
        assertTrue(
            "a provider reading must not be exposed to the overlay as a pronunciation",
            outcome.lines.none { !it.roma.isNullOrBlank() },
        )
        assertEquals("none", outcome.pronunciationSource)
    }

    @Test
    fun `a chinese transliteration lane is not exposed to the overlay as a pronunciation`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_DOCUMENT_WITH_CHINESE_TRANSLITERATION,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.NE,
                    lines = OnlineTranslationExtraction.extract(
                        LyricsResult(
                            tags = emptyMap(),
                            original = listOf(
                                line(1_000L, 2_500L, "夢ならば"),
                                line(2_500L, 5_000L, "ありがとう"),
                            ),
                            translated = listOf(
                                line(1_000L, 2_500L, "如果这一切"),
                                line(2_500L, 5_000L, "谢谢"),
                            ),
                            romanization = null,
                        ),
                    ),
                ),
            ),
            durationMs = 5_000L,
        )

        assertNotNull(outcome)
        assertTrue(
            "Chinese must never be published as the pronunciation lane",
            outcome!!.lines.none { line ->
                line.roma?.let(LyricLaneClassifier::isTranslationLike) == true
            },
        )
        assertEquals("如果这一切", outcome.lines[0].translation)
        assertEquals("none", outcome.pronunciationSource)
    }

    private fun line(start: Long, end: Long, text: String) =
        LyricsLine(start, end, listOf(LyricsWord(start, end, text)))

    private companion object {
        const val HEAD = "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
            "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
            "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
            "itunes:timing=\"Word\" xml:lang=\"ja\">"

        const val BODY = "<body dur=\"0:05.000\"><div begin=\"0.000\" end=\"5.000\">" +
            "<p begin=\"1.000\" end=\"2.500\" ttm:agent=\"v1\" itunes:key=\"L1\">" +
            "<span begin=\"1.000\" end=\"1.700\">夢</span>" +
            "<span begin=\"1.700\" end=\"2.500\">ならば</span></p>" +
            "<p begin=\"2.500\" end=\"5.000\" ttm:agent=\"v1\" itunes:key=\"L2\">" +
            "<span begin=\"2.500\" end=\"3.500\">ありがとう</span></p>" +
            "</div></body></tt>"

        const val ITUNES_HEAD = "<iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\"/>"

        val JAPANESE_DOCUMENT = HEAD + "<head><metadata>" + ITUNES_HEAD +
            "</metadata></head>" + BODY

        /** 一条被写进 `<transliterations>` 车道的中文（旧版本就是这么存的）。 */
        val JAPANESE_DOCUMENT_WITH_CHINESE_TRANSLITERATION = HEAD +
            "<head><metadata><iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            "<transliterations><transliteration xml:lang=\"zh-Hans\">" +
            "<text for=\"L1\">如果这一切</text><text for=\"L2\">谢谢</text>" +
            "</transliteration></transliterations>" +
            "</iTunesMetadata></metadata></head>" + BODY
    }
}
