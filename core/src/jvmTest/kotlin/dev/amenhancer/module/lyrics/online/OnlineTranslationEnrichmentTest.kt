package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
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

    private fun thirdParty() = OnlineTranslationCandidate(
        source = Source.NE,
        lines = listOf(
            OnlineTranslationLine(1_000L, "Hello world", translation = "你好，世界"),
            OnlineTranslationLine(2_500L, "Second line", translation = "第二行"),
        ),
    )

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
    }
}
