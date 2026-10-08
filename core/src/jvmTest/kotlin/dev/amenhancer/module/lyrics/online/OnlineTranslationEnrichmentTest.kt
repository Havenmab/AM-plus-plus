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
 * gets the online lane with its word timing intact, a fully-Chinese song is not
 * touched, and every failure mode returns null rather than a document.
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
    fun `an Apple translated document still receives the online pronunciation lane`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_TRANSLATED_DOCUMENT,
            candidates = listOf(pronunciationOnlyCandidate()),
            pronunciationRequested = true,
        )

        assertNotNull("Apple's translation blocked the pronunciation pass", outcome)
        val ttml = outcome!!.ttml
        // Apple's own translation lane survives; only a transliterations lane is added.
        assertTrue(ttml.contains(">你的名字<"))
        assertTrue(ttml.contains(">谢谢<"))
        assertTrue(
            ttml.contains(
                "<transliterations><transliteration xml:lang=\"und-Latn\">" +
                    "<text for=\"L1\">Kimi no na wa</text>",
            ),
        )
        assertEquals("apple", outcome.translationSource)
        assertEquals("NE", outcome.pronunciationSource)
        assertEquals(2, outcome.pronunciationLines)
        assertEquals(bodyOf(JAPANESE_TRANSLATED_DOCUMENT), bodyOf(ttml))
    }

    @Test
    fun `pronunciation is not requested by default`() {
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = JAPANESE_TRANSLATED_DOCUMENT,
                candidates = listOf(pronunciationOnlyCandidate()),
            ),
        )
    }

    @Test
    fun `a fully chinese song receives only the pronunciation lane`() {
        val outcome = OnlineTranslationEnrichment.enrich(
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
            pronunciationRequested = true,
        )

        assertNotNull("a fully-Chinese song must still take pronunciation", outcome)
        val ttml = outcome!!.ttml
        assertTrue(ttml.contains("<transliterations>"))
        assertFalse("a fully-Chinese song must not take a translation lane", ttml.contains("<translations>"))
        assertTrue(ttml.contains(">gan xie ni ceng lai guo<"))
        assertEquals("none", outcome.translationSource)
        assertEquals("QM", outcome.pronunciationSource)
        assertEquals(2, outcome.pronunciationLines)
    }

    @Test
    fun `an English line never receives an English pronunciation`() {
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
            pronunciationRequested = true,
        )

        assertNotNull(outcome)
        assertFalse(
            "a Latin-only Apple line must not get a transliterations entry",
            outcome!!.ttml.contains("<transliterations>"),
        )
        assertEquals("NE", outcome.translationSource)
        assertEquals("none", outcome.pronunciationSource)
        assertEquals(0, outcome.pronunciationLines)
    }

    @Test
    fun `the Mandarin hide switch suppresses only the pronunciation lane`() {
        val candidate = OnlineTranslationCandidate(
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
        )

        val hidden = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
            candidates = listOf(candidate),
            pronunciationRequested = true,
            hideMandarinPronunciation = true,
            genre = "Mandopop",
        )
        assertNotNull("translation must still publish on a hidden Mandarin song", hidden)
        assertTrue(hidden!!.ttml.contains("<translations>"))
        assertFalse(hidden.ttml.contains("<transliterations>"))
        assertEquals("NE", hidden.translationSource)
        assertEquals("none", hidden.pronunciationSource)

        val cantonese = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
            candidates = listOf(candidate),
            pronunciationRequested = true,
            hideMandarinPronunciation = true,
            genre = "Cantopop",
        )
        assertNotNull(cantonese)
        assertTrue(cantonese!!.ttml.contains("<transliterations>"))
        assertEquals("NE", cantonese.pronunciationSource)

        val switchOff = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
            candidates = listOf(candidate),
            pronunciationRequested = true,
            hideMandarinPronunciation = false,
            genre = "Mandopop",
        )
        assertNotNull(switchOff)
        assertTrue(switchOff!!.ttml.contains("<transliterations>"))
    }

    @Test
    fun `a pronunciation-only candidate is accepted only when pronunciation is requested`() {
        assertNull(
            OnlineTranslationEnrichment.enrich(
                ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
                candidates = listOf(pronunciationOnlyCandidate()),
                pronunciationRequested = false,
            ),
        )
        assertNotNull(
            OnlineTranslationEnrichment.enrich(
                ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
                candidates = listOf(pronunciationOnlyCandidate()),
                pronunciationRequested = true,
            ),
        )
    }

    @Test
    fun `the merged lanes are exposed per line for the native-model overlay`() {
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

        val romanized = OnlineTranslationEnrichment.enrich(
            ttml = JAPANESE_UNTRANSLATED_DOCUMENT,
            candidates = listOf(pronunciationOnlyCandidate()),
            pronunciationRequested = true,
            durationMs = 5_000L,
        )!!

        assertEquals("Kimi no na wa", romanized.lines.mapNotNull { it.roma }.firstOrNull())
        assertEquals(2, romanized.lines.count { !it.roma.isNullOrBlank() })
        assertNull(romanized.lines[0].translation)
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

    private fun bodyOf(ttml: String): String {
        val start = ttml.indexOf("<body")
        val end = ttml.indexOf("</body>")
        require(start >= 0 && end > start) { "no body in document" }
        return ttml.substring(start, end + "</body>".length)
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

        /** Non-Latin Apple lyrics, so `RomanizationPolicy` can accept a pronunciation. */
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

        val JAPANESE_UNTRANSLATED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\"/></metadata></head>" +
            JAPANESE_BODY

        val JAPANESE_TRANSLATED_DOCUMENT = HEAD +
            "<head><metadata><iTunesMetadata " +
            "xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            JAPANESE_TRANSLATIONS + "</iTunesMetadata></metadata></head>" + JAPANESE_BODY
    }
}
