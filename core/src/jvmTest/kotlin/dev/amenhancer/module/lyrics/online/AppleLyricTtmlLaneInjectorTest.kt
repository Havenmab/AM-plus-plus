package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for writing an enrichment lane into Apple's own document:
 * the body is copied byte for byte (word spans, the raw whitespace between them
 * and `x-bg` background markup included), the lane is keyed by Apple's own
 * `itunes:key`, an existing lane is replaced rather than duplicated, and a lane
 * the document already owns is left alone.
 */
class AppleLyricTtmlLaneInjectorTest {

    @Test
    fun `an enriched apple document keeps its body byte identical`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = APPLE_WORD_DOCUMENT,
            candidates = listOf(translationCandidate()),
            durationMs = 10_000L,
        )

        assertNotNull("the untranslated document was not enriched", outcome)
        assertEquals(bodyOf(APPLE_WORD_DOCUMENT), bodyOf(outcome!!.ttml))
        // The exact losses the old writer round trip caused are still here.
        assertTrue(
            "inter-span whitespace vanished from the body",
            outcome.ttml.contains(
                "<span begin=\"0:01.000\" end=\"0:01.400\">Nice</span> " +
                    "<span begin=\"0:01.400\" end=\"0:01.700\">to</span>",
            ),
        )
        assertTrue(
            "the x-bg background group was flattened away",
            outcome.ttml.contains("ttm:role=\"x-bg\""),
        )
    }

    @Test
    fun `the injected lane holds one entry per keyed line`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = APPLE_WORD_DOCUMENT,
            candidates = listOf(translationCandidate()),
            durationMs = 10_000L,
        )

        assertNotNull(outcome)
        val ttml = outcome!!.ttml
        assertEquals(1, Regex("""<translations>""").findAll(ttml).count())
        assertEquals(2, Regex("""<text for=""").findAll(ttml).count())
        assertTrue(ttml.contains("<text for=\"L1\">很高兴见到你，你去过哪里？</text>"))
        assertTrue(ttml.contains("<text for=\"L2\">得到了，噢耶，宝贝</text>"))
    }

    @Test
    fun `an already translated line keeps apple's own text`() {
        val lines = AppleLyricTtmlReader.read(TRANSLATED_DOCUMENT)
        assertEquals(listOf("旧一", "旧二"), lines.map(AppleTtmlLine::translation))
        // The merge only fills the line Apple left untranslated.
        val merged = lines.mapIndexed { index, line ->
            if (index == 1) line.copy(translation = "新二") else line
        }

        val injected = AppleLyricTtmlLaneInjector.inject(TRANSLATED_DOCUMENT, merged)

        assertNotNull(injected)
        assertEquals(bodyOf(TRANSLATED_DOCUMENT), bodyOf(injected!!))
        assertEquals(1, Regex("""<translations>""").findAll(injected).count())
        assertTrue(injected.contains("<text for=\"L1\">旧一</text>"))
        assertTrue(injected.contains("<text for=\"L2\">新二</text>"))
    }

    @Test
    fun `an untranslated line holds the documented placeholder`() {
        val lines = AppleLyricTtmlReader.read(APPLE_WORD_DOCUMENT)
        val merged = lines.mapIndexed { index, line ->
            if (index == 0) line.copy(translation = "你好") else line
        }

        val injected = AppleLyricTtmlLaneInjector.inject(APPLE_WORD_DOCUMENT, merged)

        assertNotNull(injected)
        assertTrue(
            injected!!.contains(
                "<text for=\"L1\">你好</text><text for=\"L2\"> </text>",
            ),
        )
    }

    @Test
    fun `a self closing iTunesMetadata is opened to hold the lane`() {
        val document = Regex("""(?is)<iTunesMetadata\b[^>]*>.*?</iTunesMetadata\s*>""").replace(
            APPLE_WORD_DOCUMENT,
            "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\"/>",
        )
        val lines = AppleLyricTtmlReader.read(document).mapIndexed { index, line ->
            if (index == 0) line.copy(translation = "你好") else line
        }

        val injected = AppleLyricTtmlLaneInjector.inject(document, lines)

        assertNotNull(injected)
        assertTrue(
            injected!!.contains(
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
                    "<translations>",
            ),
        )
        assertFalse(injected.contains("/><translations>"))
        assertEquals(bodyOf(document), bodyOf(injected))
    }

    @Test
    fun `an existing transliterations lane is left alone`() {
        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = ROMANIZED_DOCUMENT,
            candidates = listOf(translationCandidate()),
            durationMs = 10_000L,
        )

        assertNotNull(outcome)
        val ttml = outcome!!.ttml
        assertEquals(
            transliterationsOf(ROMANIZED_DOCUMENT),
            transliterationsOf(ttml),
        )
        assertTrue(ttml.contains("<translations>"))
    }

    @Test
    fun `romanization the winner supplies opens a transliterations lane`() {
        val candidate = OnlineTranslationCandidate(
            source = Source.NE,
            lines = listOf(
                OnlineTranslationLine(
                    startTimeMs = 1_000L,
                    content = "Nice to meet you, where you been?",
                    translation = "很高兴见到你，你去过哪里？",
                    romanization = "Nais tu mit yu",
                ),
                OnlineTranslationLine(
                    startTimeMs = 5_000L,
                    content = "Got Ooh yeah baby",
                    translation = "得到了，噢耶，宝贝",
                    romanization = "Got Ooh yeah baby",
                ),
            ),
        )

        val outcome = OnlineTranslationEnrichment.enrich(
            ttml = APPLE_WORD_DOCUMENT,
            candidates = listOf(candidate),
            durationMs = 10_000L,
        )

        assertNotNull(outcome)
        val ttml = outcome!!.ttml
        assertTrue(
            ttml.contains(
                "<transliterations><transliteration xml:lang=\"ko-Latn\">" +
                    "<text for=\"L1\">Nais tu mit yu</text>",
            ),
        )
        assertEquals(bodyOf(APPLE_WORD_DOCUMENT), bodyOf(ttml))
    }

    private fun translationCandidate() = OnlineTranslationCandidate(
        source = Source.NE,
        lines = listOf(
            OnlineTranslationLine(
                startTimeMs = 1_000L,
                content = "Nice to meet you, where you been?",
                translation = "很高兴见到你，你去过哪里？",
            ),
            OnlineTranslationLine(
                startTimeMs = 5_000L,
                content = "Got Ooh yeah baby",
                translation = "得到了，噢耶，宝贝",
            ),
        ),
    )

    private companion object {
        /** Apple-shaped: word spans with raw spaces and an `x-bg` group, no lane. */
        val APPLE_WORD_DOCUMENT = """
            <?xml version='1.0' encoding='utf-8'?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word" xml:lang="en">
              <head><metadata>
                <ttm:agent type="person" xml:id="v1"/>
                <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                  <songwriters><songwriter>Max Martin</songwriter></songwriters>
                </iTunesMetadata>
              </metadata></head>
              <body dur="0:10.000">
                <div begin="0:00.000" end="0:10.000" itunes:song-part="Verse">
                  <p begin="0:01.000" end="0:04.000" ttm:agent="v1" itunes:key="L1"><span begin="0:01.000" end="0:01.400">Nice</span> <span begin="0:01.400" end="0:01.700">to</span> <span begin="0:01.700" end="0:02.100">meet</span> <span begin="0:02.100" end="0:02.500">you,</span> <span begin="0:02.500" end="0:03.000">where</span> <span begin="0:03.000" end="0:03.400">you</span> <span begin="0:03.400" end="0:04.000">been?</span></p>
                  <p begin="0:05.000" end="0:08.000" ttm:agent="v1" itunes:key="L2"><span begin="0:05.000" end="0:05.600">Got</span> <span ttm:role="x-bg" begin="0:05.600" end="0:07.000"><span begin="0:05.600" end="0:06.200">Ooh</span> <span begin="0:06.200" end="0:07.000">yeah</span></span> <span begin="0:07.000" end="0:08.000">baby</span></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        /** The same body with a lane Apple already wrote. */
        val TRANSLATED_DOCUMENT = APPLE_WORD_DOCUMENT.replace(
            "<songwriters><songwriter>Max Martin</songwriter></songwriters>",
            "<songwriters><songwriter>Max Martin</songwriter></songwriters>" +
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                "<text for=\"L1\">旧一</text><text for=\"L2\">旧二</text>" +
                "</translation></translations>",
        )

        /** No translation lane, but Apple already supplies romanization. */
        val ROMANIZED_DOCUMENT = APPLE_WORD_DOCUMENT.replace(
            "<songwriters><songwriter>Max Martin</songwriter></songwriters>",
            "<songwriters><songwriter>Max Martin</songwriter></songwriters>" +
                "<transliterations><transliteration xml:lang=\"en-Latn\">" +
                "<text for=\"L1\">Nais tu mit yu</text><text for=\"L2\">Got Ooh yeah baby</text>" +
                "</transliteration></transliterations>",
        )

        fun bodyOf(ttml: String): String {
            val start = ttml.indexOf("<body")
            val end = ttml.indexOf("</body>")
            require(start >= 0 && end > start) { "no body in document" }
            return ttml.substring(start, end + "</body>".length)
        }

        fun transliterationsOf(ttml: String): String {
            val start = ttml.indexOf("<transliterations>")
            val end = ttml.indexOf("</transliterations>")
            require(start >= 0 && end > start) { "no transliterations in document" }
            return ttml.substring(start, end + "</transliterations>".length)
        }
    }
}
