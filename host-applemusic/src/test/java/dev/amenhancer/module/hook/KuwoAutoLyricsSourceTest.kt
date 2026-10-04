package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * JVM coverage for the search-based Kuwo AutoLyricsSource: track identity,
 * the ported match policy, Apple TTML output and fail-open behaviour. All
 * network access is a fake transport.
 */
class KuwoAutoLyricsSourceTest {

    @Test
    fun `matches the current track and writes line timed Apple TTML`() {
        val requests = mutableListOf<String>()
        val source = source(transport(requests), CurrentSongDetails(42L, "Song", "Artist", 215_000L))

        val ttml = source.fetch(42L)!!

        assertTrue(ttml.contains("Hello"))
        assertTrue(ttml.contains("World"))
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(requests.first().contains("key=Song+Artist"))
    }

    @Test
    fun `does nothing when the playing track is not the requested id`() {
        val requests = mutableListOf<String>()
        val source = source(transport(requests), CurrentSongDetails(42L, "Song", "Artist", 215_000L))

        assertNull(source.fetch(43L))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a candidate that fails the match score is rejected without fetching lyrics`() {
        val requests = mutableListOf<String>()
        val source = source(
            transport(requests, searchBody = MISMATCH_SEARCH),
            CurrentSongDetails(42L, "Song", "Artist", 215_000L),
        )

        assertNull(source.fetch(42L))
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("searchMusicBykeyWord"))
    }

    @Test
    fun `a throwing transport yields null and cannot escape`() {
        val transport = object : LyricHttpTransport {
            override fun get(url: String): String? = throw IllegalStateException("network down")
            override fun getBytes(url: String): ByteArray? =
                throw IllegalStateException("network down")

            override fun getBytes(url: String, headers: Map<String, String>): ByteArray? =
                throw IllegalStateException("network down")
        }
        val source = source(transport, CurrentSongDetails(42L, "Song", "Artist", 215_000L))

        assertNull(source.fetch(42L))
    }

    @Test
    fun `a throwing kuwo source leaves the other sources working`() {
        val failing = source(
            object : LyricHttpTransport {
                override fun get(url: String): String? = throw IllegalStateException("network down")
                override fun getBytes(url: String): ByteArray? =
                    throw IllegalStateException("network down")
            },
            CurrentSongDetails(42L, "Song", "Artist", 215_000L),
        )
        val resolver = AutoLyricsSourceResolver(
            listOf(
                failing,
                AutoLyricsSource("amll") { WORD_TTML },
            ),
        )

        assertEquals(AutoLyricsCandidate("amll", WORD_TTML), resolver.fetch(42L))
    }

    private fun source(
        transport: LyricHttpTransport,
        details: CurrentSongDetails?,
    ): AutoLyricsSource = KuwoAutoLyricsSource.create(transport, { details }).autoLyricsSource()

    /** Search succeeds, LRCX misses, and the plain lyric endpoint answers. */
    private fun transport(
        requests: MutableList<String>,
        searchBody: String = MATCHING_SEARCH,
    ): LyricHttpTransport = object : LyricHttpTransport {
        override fun get(url: String): String? = getBytes(url)?.toString(StandardCharsets.UTF_8)

        override fun getBytes(url: String): ByteArray? = dispatch(url)

        override fun getBytes(url: String, headers: Map<String, String>): ByteArray? = dispatch(url)

        private fun dispatch(url: String): ByteArray? {
            requests += url
            return when {
                url.contains("searchMusicBykeyWord") -> searchBody.toByteArray(StandardCharsets.UTF_8)
                url.contains("getlyric") -> LYRICS_BODY.toByteArray(StandardCharsets.UTF_8)
                else -> null
            }
        }
    }

    private companion object {
        const val MATCHING_SEARCH =
            "{\"data\":{\"list\":[{\"rid\":123,\"name\":\"Song\",\"artist\":\"Artist\"," +
                "\"album\":\"Album\",\"duration\":215}]}}"

        const val MISMATCH_SEARCH =
            "{\"data\":{\"list\":[{\"rid\":9,\"name\":\"Other\",\"artist\":\"Nobody\"," +
                "\"album\":\"Elsewhere\",\"duration\":100}]}}"

        const val LYRICS_BODY =
            "{\"data\":{\"lrclist\":[{\"time\":\"1.0\",\"lineLyric\":\"Hello\"}," +
                "{\"time\":\"3.0\",\"lineLyric\":\"World\"}]}}"

        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
    }
}
