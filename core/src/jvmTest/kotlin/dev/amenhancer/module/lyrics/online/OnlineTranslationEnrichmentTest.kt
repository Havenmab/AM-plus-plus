package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM end-to-end coverage for the enrichment decision and merge over
 * fixtures: Apple's own translation lane wins, an untranslated foreign document
 * gets the online translation lane with its word timing intact, a fully-Chinese
 * song is not touched, and every failure mode returns null rather than a
 * document.
 *
 * The pronunciation half is gone: a provider's romanization column is read past,
 * no `<transliterations>` track is ever injected, and a pronunciation-only
 * candidate has nothing to contribute.
 */
class OnlineTranslationEnrichmentTest {

    @Test
    fun `an already translated Apple document is left untouched`() {
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = TRANSLATED_DOCUMENT,
                candidates = listOf(thirdParty()),
            ),
        )
    }

    @Test
    fun `a partial Apple translation lane still wins`() {
        val partiallyTranslated = TRANSLATED_DOCUMENT.replace("<text for=\"L2\">第二行</text>", "")

        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = partiallyTranslated,
                candidates = listOf(thirdParty()),
            ),
        )
    }

    @Test
    fun `an untranslated Apple document receives the online translation lane`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = UNTRANSLATED_DOCUMENT,
            candidates = listOf(thirdParty()),
            durationMs = 5_000L,
        )

        assertNotNull(outcome)
        assertEquals(Source.NE, outcome!!.source)
        assertEquals(2, outcome.matchedLines)
        assertEquals(2, outcome.totalLines)
        assertTrue(outcome.ttml.contains("<translations>"))
        assertTrue(outcome.ttml.contains(">你好，世界<"))
        assertTrue(outcome.ttml.contains(">第二行<"))
        // Apple's Word timing and word spans survive the merge.
        assertTrue(outcome.ttml.contains("itunes:timing=\"Word\""))
        assertTrue(outcome.ttml.contains("<span begin=\"1.000\" end=\"1.700\">Hello</span>"))
    }

    @Test
    fun `a fully chinese song is not enriched`() {
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = CHINESE_DOCUMENT,
                candidates = listOf(
                    OnlineTranslationCandidate(
                        source = Source.QM,
                        lines = listOf(
                            OnlineTranslationLine(1_000L, "感谢你曾来过", translation = "Thanks"),
                            OnlineTranslationLine(2_000L, "我早已明白了", translation = "I knew"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a candidate without a usable translation lane does not pass`() {
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = UNTRANSLATED_DOCUMENT,
                candidates = listOf(
                    OnlineTranslationCandidate(
                        source = Source.KUWO,
                        lines = listOf(
                            OnlineTranslationLine(1_000L, "Hello world", translation = "// //"),
                            OnlineTranslationLine(2_500L, "Second line", translation = null),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a mismatching candidate is rejected`() {
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = UNTRANSLATED_DOCUMENT,
                candidates = listOf(
                    OnlineTranslationCandidate(
                        source = Source.KUGOU,
                        lines = listOf(
                            OnlineTranslationLine(1_000L, "Completely different song", translation = "无关"),
                            OnlineTranslationLine(2_500L, "Nothing alike here", translation = "无关"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `no candidates leaves the document untouched`() {
        assertNull(OnlineTranslationEnrichment.enrich(UNTRANSLATED_DOCUMENT, emptyList()))
    }

    @Test
    fun `a malformed document is left untouched`() {
        assertNull(OnlineTranslationEnrichment.enrich("garbage", listOf(thirdParty())))
        assertNull(OnlineTranslationEnrichment.enrich("", listOf(thirdParty())))
    }

    @Test
    fun `the best translation source wins by coverage`() {
        val partial = OnlineTranslationCandidate(
            source = Source.KUWO,
            lines = listOf(OnlineTranslationLine(1_000L, "Hello world", translation = "你好")),
        )
        val full = thirdParty()

        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = UNTRANSLATED_DOCUMENT,
            candidates = listOf(partial, full),
        )

        assertNotNull(outcome)
        assertEquals(Source.NE, outcome!!.source)
        assertEquals(2, outcome.matchedLines)
    }

    @Test
    fun `a pronunciation-only candidate never publishes`() {
        // Apple's own translation is already there and the provider adds only a
        // romanization, which the Apple-only policy never publishes.
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = JAPANESE_TRANSLATED_DOCUMENT,
                candidates = listOf(pronunciationOnlyCandidate()),
            ),
        )
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
                candidates = listOf(pronunciationOnlyCandidate()),
            ),
        )
    }

    @Test
    fun `a fully chinese song never receives a transliteration lane`() {
        // Even a provider romanization of fully-Chinese lyrics is dropped: the
        // translation half will not fire for a fully-Chinese song and the
        // pronunciation half does not exist.
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = CHINESE_ROMANIZABLE_DOCUMENT,
                candidates = listOf(
                    OnlineTranslationCandidate(
                        source = Source.QM,
                        lines = listOf(
                            OnlineTranslationLine(
                                startTimeMs = 1_000L,
                                content = "感谢你曾来过",
                                translation = "Thanks for coming",
                                romanization = "gan xie ni ceng lai guo",
                            ),
                            OnlineTranslationLine(
                                startTimeMs = 2_000L,
                                content = "我早已明白了",
                                translation = "I understood long ago",
                                romanization = "wo zao yi ming bai le",
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `an English line's provider romanization is dropped`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = UNTRANSLATED_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.NE,
                    lines = listOf(
                        OnlineTranslationLine(
                            startTimeMs = 1_000L,
                            content = "Hello world",
                            translation = "你好，世界",
                            romanization = "Haro warudo",
                        ),
                        OnlineTranslationLine(
                            startTimeMs = 2_500L,
                            content = "Second line",
                            translation = "第二行",
                            romanization = "Haro warudo",
                        ),
                    ),
                ),
            ),
        )

        assertNotNull(outcome)
        assertFalse(
            "no transliterations track may be injected",
            outcome!!.ttml.contains("<transliterations>"),
        )
        assertEquals("NE", outcome.translationSource)
        assertEquals("none", outcome.pronunciationSource)
        assertEquals(0, outcome.pronunciationLines)
    }

    @Test
    fun `an Apple transliterations lane is preserved untouched`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_ROMANIZED_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.NE,
                    lines = listOf(
                        OnlineTranslationLine(1_000L, "君の名は", translation = "你的名字"),
                        OnlineTranslationLine(2_500L, "ありがとう", translation = "谢谢"),
                    ),
                ),
            ),
        )

        assertNotNull(outcome)
        val ttml = outcome!!.ttml
        assertEquals(transliterationsOf(JAPANESE_ROMANIZED_DOCUMENT), transliterationsOf(ttml))
        assertTrue(ttml.contains("<translations>"))
        assertEquals("apple", outcome.pronunciationSource)
        assertEquals(2, outcome.pronunciationLines)
    }

    @Test
    fun `the translation lane is exposed per line for the native-model overlay`() {
        val translated = OnlineTranslationEnrichment.enrich(
            ttml = UNTRANSLATED_DOCUMENT,
            candidates = listOf(thirdParty()),
            durationMs = 5_000L,
        )!!

        assertEquals(translated.totalLines, translated.lines.size)
        assertEquals("你好，世界", translated.lines[0].translation)
        assertEquals("第二行", translated.lines[1].translation)
        assertEquals(1_000L, translated.lines[0].begin)
        assertEquals(2_500L, translated.lines[0].end)
    }

    @Test
    fun `a provider pronunciation is never exposed to the overlay`() {
        val merged = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
            candidates = listOf(
                OnlineTranslationCandidate(
                    source = Source.NE,
                    lines = listOf(
                        OnlineTranslationLine(
                            startTimeMs = 1_000L,
                            content = "君の名は",
                            translation = "你的名字",
                            romanization = "Kimi no na wa",
                        ),
                        OnlineTranslationLine(
                            startTimeMs = 2_500L,
                            content = "ありがとう",
                            translation = "谢谢",
                            romanization = "Arigatou",
                        ),
                    ),
                ),
            ),
        )!!

        assertEquals("你的名字", merged.lines[0].translation)
        assertTrue(
            "the overlay must never carry a provider romanization",
            merged.lines.none { !it.roma.isNullOrBlank() },
        )
    }

    private fun thirdParty() = OnlineTranslationCandidate(
        source = Source.NE,
        lines = listOf(
            OnlineTranslationLine(1_000L, "Hello world", translation = "你好，世界"),
            OnlineTranslationLine(2_500L, "Second line", translation = "第二行"),
        ),
    )

    private fun pronunciationOnlyCandidate() = OnlineTranslationCandidate(
        source = Source.NE,
        lines = listOf(
            OnlineTranslationLine(
                startTimeMs = 1_000L,
                content = "君の名は",
                romanization = "Kimi no na wa",
            ),
            OnlineTranslationLine(
                startTimeMs = 2_500L,
                content = "ありがとう",
                romanization = "Arigatou",
            ),
        ),
    )

    private fun transliterationsOf(ttml: String): String {
        val start = ttml.indexOf("<transliterations>")
        val end = ttml.indexOf("</transliterations>")
        require(start >= 0 && end > start) { "no transliterations in document" }
        return ttml.substring(start, end + "</transliterations>".length)
    }

    private companion object {
        const val HEAD = "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
            "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
            "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
            "itunes:timing=\"Word\" xml:lang=\"ja\">"
        const val BODY = "<body dur=\"0:05.000\"><div begin=\"0.000\" end=\"5.000\">" +
            "<p begin=\"1.000\" end=\"2.500\" ttm:agent=\"v1\" itunes:key=\"L1\">" +
            "<span begin=\"1.000\" end=\"1.700\">Hello</span> " +
            "<span begin=\"1.700\" end=\"2.500\">world</span></p>" +
            "<p begin=\"2.500\" end=\"5.000\" ttm:agent=\"v1\" itunes:key=\"L2\">" +
            "<span begin=\"2.500\" end=\"3.500\">Second</span> " +
            "<span begin=\"3.500\" end=\"5.000\">line</span></p>" +
            "</div></body></tt>"
        const val TRANSLATIONS = "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
            "<text for=\"L1\">你好，世界</text><text for=\"L2\">第二行</text>" +
            "</translation></translations>"

        val UNTRANSLATED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\"/>" +
            "</metadata></head>" + BODY

        val TRANSLATED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            TRANSLATIONS + "</iTunesMetadata></metadata></head>" + BODY

        val CHINESE_DOCUMENT =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Line\" xml:lang=\"zh-Hans\">" +
                "<body><div>" +
                "<p begin=\"1.000\" end=\"2.000\" itunes:key=\"L1\">感谢你曾来过</p>" +
                "<p begin=\"2.000\" end=\"3.000\" itunes:key=\"L2\">我早已明白了</p>" +
                "</div></body></tt>"

        /** The same fully-Chinese song, but with Apple's head so a lane can land. */
        val CHINESE_ROMANIZABLE_DOCUMENT =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Line\" xml:lang=\"zh-Hans\">" +
                "<head><metadata><iTunesMetadata " +
                "xmlns=\"http://music.apple.com/lyric-ttml-internal\"/></metadata></head>" +
                "<body><div>" +
                "<p begin=\"1.000\" end=\"2.000\" itunes:key=\"L1\">感谢你曾来过</p>" +
                "<p begin=\"2.000\" end=\"3.000\" itunes:key=\"L2\">我早已明白了</p>" +
                "</div></body></tt>"

        /** Non-Latin Apple lyrics, so a pronunciation could have been accepted. */
        const val JAPANESE_BODY =
            "<body dur=\"0:05.000\"><div begin=\"0.000\" end=\"5.000\">" +
                "<p begin=\"1.000\" end=\"2.500\" ttm:agent=\"v1\" itunes:key=\"L1\">" +
                "<span begin=\"1.000\" end=\"1.700\">君の</span> " +
                "<span begin=\"1.700\" end=\"2.500\">名は</span></p>" +
                "<p begin=\"2.500\" end=\"5.000\" ttm:agent=\"v1\" itunes:key=\"L2\">" +
                "<span begin=\"2.500\" end=\"3.500\">ありが</span> " +
                "<span begin=\"3.500\" end=\"5.000\">とう</span></p>" +
                "</div></body></tt>"
        const val JAPANESE_TRANSLATIONS =
            "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                "<text for=\"L1\">你的名字</text><text for=\"L2\">谢谢</text>" +
                "</translation></translations>"
        const val JAPANESE_TRANSLITERATIONS =
            "<transliterations><transliteration xml:lang=\"und-Latn\">" +
                "<text for=\"L1\">Kimi no na wa</text><text for=\"L2\">Arigatou</text>" +
                "</transliteration></transliterations>"

        val JAPANESE_UNTRANSLATED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\"/></metadata></head>" +
            JAPANESE_BODY

        val JAPANESE_TRANSLATED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            JAPANESE_TRANSLATIONS + "</iTunesMetadata></metadata></head>" + JAPANESE_BODY

        /** Apple's own transliterations lane, which the merge must preserve. */
        val JAPANESE_ROMANIZED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            JAPANESE_TRANSLITERATIONS + "</iTunesMetadata></metadata></head>" + JAPANESE_BODY
    }
}
