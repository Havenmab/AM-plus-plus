package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.hook.AutoLyricsCandidate
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the narrow line-level acceptance change: only a source that opts in via
 * [AutoLyricsSource.acceptsLineTiming] may resolve with Line timing.
 */
class AutoLyricsSourceResolverTest {

    @Test
    fun `a search source that opts in resolves with line timing`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("kuwo", acceptsLineTiming = true) { LINE_TTML }),
        )

        assertEquals(AutoLyricsCandidate("kuwo", LINE_TTML), resolver.fetch(42L))
    }

    @Test
    fun `existing providers keep requiring word timing`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("amll") { LINE_TTML }),
        )

        assertNull(resolver.fetch(42L))
    }

    @Test
    fun `an existing provider still accepts word timing`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("amll") { WORD_TTML }),
        )

        assertEquals(AutoLyricsCandidate("amll", WORD_TTML), resolver.fetch(42L))
    }

    @Test
    fun `line acceptance does not skip structural validation`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("kuwo", acceptsLineTiming = true) { "<tt><p>no body</p></tt>" },
            ),
        )

        assertNull(resolver.fetch(42L))
    }

    @Test
    fun `a rejected line-only source does not consume the lookup`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll") {
                    calls += "amll"
                    LINE_TTML
                },
                AutoLyricsSource("kuwo", acceptsLineTiming = true) {
                    calls += "kuwo"
                    LINE_TTML
                },
            ),
        )

        assertEquals(AutoLyricsCandidate("kuwo", LINE_TTML), resolver.fetch(42L))
        assertEquals(listOf("amll", "kuwo"), calls)
    }

    @Test
    fun `a throwing source is skipped and the next source still resolves`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("kuwo", acceptsLineTiming = true) { error("network down") },
                AutoLyricsSource("amll") { WORD_TTML },
            ),
        )

        assertEquals(AutoLyricsCandidate("amll", WORD_TTML), resolver.fetch(42L))
    }

    @Test
    fun `a leading search source is consulted before every fixed provider`() {
        val fixedRequests = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver.fixed(
            amll = AmllTtmlClient(recordingTransport(fixedRequests)),
            amLyrics = AmLyricsClient(recordingTransport(fixedRequests)),
            lunabeat = LunabeatClient(
                indexTransport = recordingTransport(fixedRequests),
                cache = { null },
            ),
            leading = listOf(AutoLyricsSource("kuwo", acceptsLineTiming = true) { LINE_TTML }),
        )

        assertEquals(AutoLyricsCandidate("kuwo", LINE_TTML), resolver.fetch(42L))
        assertTrue(fixedRequests.isEmpty())
    }

    @Test
    fun `without a leading source the fixed provider order is untouched`() {
        val fixedRequests = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver.fixed(
            amll = AmllTtmlClient(recordingTransport(fixedRequests)),
            amLyrics = AmLyricsClient(recordingTransport(fixedRequests)),
            lunabeat = LunabeatClient(
                indexTransport = recordingTransport(fixedRequests),
                cache = { null },
            ),
        )

        assertNull(resolver.fetch(42L))
        assertEquals(
            listOf(
                "${AmllTtmlClient.AMLL_TTML_DB_BASE}/am-lyrics/42.ttml",
                LunabeatClient.MANIFEST_URL,
                AmLyricsClient.AM_LYRICS_INDEX_URL,
            ),
            fixedRequests,
        )
    }

    @Test
    fun `a trailing search source is never consulted while a fixed provider resolves`() {
        val order = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver.fixed(
            amll = AmllTtmlClient(answeringTransport(order, WORD_TTML)),
            amLyrics = AmLyricsClient(recordingTransport(order)),
            lunabeat = LunabeatClient(
                indexTransport = recordingTransport(order),
                cache = { null },
            ),
            trailing = listOf(AutoLyricsSource("online-search", acceptsLineTiming = true) {
                order += "online-search"
                LINE_TTML
            }),
        )

        val candidate = resolver.fetch(42L)

        assertEquals(CustomLyricsSources.AMLL, candidate?.source)
        assertEquals(listOf("${AmllTtmlClient.AMLL_TTML_DB_BASE}/am-lyrics/42.ttml"), order)
    }

    @Test
    fun `the trailing search source runs only after every fixed provider misses`() {
        val order = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver.fixed(
            amll = AmllTtmlClient(recordingTransport(order)),
            amLyrics = AmLyricsClient(recordingTransport(order)),
            lunabeat = LunabeatClient(
                indexTransport = recordingTransport(order),
                cache = { null },
            ),
            trailing = listOf(AutoLyricsSource("online-search", acceptsLineTiming = true) {
                order += "online-search"
                LINE_TTML
            }),
        )

        val candidate = resolver.fetch(42L)

        assertEquals("online-search", candidate?.source)
        assertEquals(
            listOf(
                "${AmllTtmlClient.AMLL_TTML_DB_BASE}/am-lyrics/42.ttml",
                LunabeatClient.MANIFEST_URL,
                AmLyricsClient.AM_LYRICS_INDEX_URL,
                "online-search",
            ),
            order,
        )
    }

    /** A transport that records every fixed-provider URL and answers nothing. */
    private fun recordingTransport(requests: MutableList<String>): LyricHttpTransport =
        object : LyricHttpTransport {
            override fun get(url: String): String? {
                requests += url
                return null
            }

            override fun getBytes(url: String): ByteArray? {
                requests += url
                return null
            }
        }

    /** A transport that records the URL and answers every request with [body]. */
    private fun answeringTransport(requests: MutableList<String>, body: String): LyricHttpTransport =
        object : LyricHttpTransport {
            override fun get(url: String): String? {
                requests += url
                return body
            }

            override fun getBytes(url: String): ByteArray? {
                requests += url
                return body.toByteArray()
            }
        }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
    }
}
