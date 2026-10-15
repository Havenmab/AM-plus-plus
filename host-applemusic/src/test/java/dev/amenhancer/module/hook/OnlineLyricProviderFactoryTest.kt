package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.OnlineLyricSources
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the online search providers as seen through the composite
 * chain: the provider factory stays offline at construction, the HTTP path
 * still writes line-timed Apple TTML for the current track, and every failure
 * stays inside the chain so the resolver keeps its other sources. All network
 * access is a fake transport; constructing a provider must not call out.
 */
class OnlineLyricProviderFactoryTest {

    @Test
    fun `constructs one offline provider per known source id and none for an unknown id`() {
        // Constructing a provider must not touch the network: every transport
        // call here throws, and construction still has to succeed.
        val offline = offlineTransport()

        assertEquals(
            OnlineLyricSources.DEFAULT_ORDER,
            OnlineLyricSources.DEFAULT_ORDER.filter { onlineLyricProviderFor(it, offline) != null },
        )
        assertNull(onlineLyricProviderFor("unknown", offline))
    }

    @Test
    fun `each known source id resolves to its own provider type`() {
        val offline = offlineTransport()

        assertEquals(
            Source.NE,
            onlineLyricProviderFor(CustomLyricsSources.NETEASE, offline)?.sourceType,
        )
        assertEquals(
            Source.QM,
            onlineLyricProviderFor(CustomLyricsSources.QQ, offline)?.sourceType,
        )
        assertEquals(
            Source.KUWO,
            onlineLyricProviderFor(CustomLyricsSources.KUWO, offline)?.sourceType,
        )
        assertEquals(
            Source.KUGOU,
            onlineLyricProviderFor(CustomLyricsSources.KUGOU, offline)?.sourceType,
        )
    }

    @Test
    fun `matches the current track and writes line timed Apple TTML`() {
        val requests = mutableListOf<String>()
        val source = chain(transport(requests), TRACK)

        val ttml = source.fetch(42L)!!

        assertTrue(ttml.contains("Hello"))
        assertTrue(ttml.contains("World"))
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(requests.first().contains("key=Song+Artist"))
    }

    @Test
    fun `does nothing when the playing track is not the requested id`() {
        val requests = mutableListOf<String>()
        val source = chain(transport(requests), TRACK)

        assertNull(source.fetch(43L))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a candidate that fails the match score is rejected without fetching lyrics`() {
        val requests = mutableListOf<String>()
        val source = chain(transport(requests, searchBody = MISMATCH_SEARCH), TRACK)

        assertNull(source.fetch(42L))
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("searchMusicBykeyWord"))
    }

    @Test
    fun `a throwing transport yields null and cannot escape`() {
        val source = chain(offlineTransport(), TRACK)

        assertNull(source.fetch(42L))
    }

    @Test
    fun `a throwing chain leaves the other resolver sources working`() {
        val failing = chain(offlineTransport(), TRACK)
        val resolver = AutoLyricsSourceResolver(
            listOf(
                failing,
                AutoLyricsSource("amll") { WORD_TTML },
            ),
        )

        assertEquals(AutoLyricsCandidate("amll", WORD_TTML), resolver.fetch(42L))
    }

    private fun chain(
        transport: LyricHttpTransport,
        details: CurrentSongDetails?,
        mode: LyricSelectionMode = LyricSelectionMode.FIRST_PASSING,
        sourceId: String = CustomLyricsSources.KUWO,
    ): AutoLyricsSource = CompositeOnlineSearchAutoLyricsSource.create(
        mode = mode,
        providers = listOf(
            OnlineLyricProvider(sourceId, onlineLyricProviderFor(sourceId, transport)!!),
        ),
        currentTrack = { details },
        // Keep the JVM test off the Android-backed ProviderLogger.info sink.
        visibleLog = {},
    ).autoLyricsSource()

    private fun offlineTransport(): LyricHttpTransport = object : LyricHttpTransport {
        override fun get(url: String): String? = throw IllegalStateException("network down")
        override fun getBytes(url: String): ByteArray? =
            throw IllegalStateException("network down")
    }

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
        val TRACK = CurrentSongDetails(
            appleMusicId = 42L,
            title = "Song",
            artist = "Artist",
            durationMs = 215_000L,
        )

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
