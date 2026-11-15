package dev.amenhancer.module.lyrics

import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The self-hosted mirror contract the app relies on: a configured root is the *first*
 * endpoint of both GitHub-hosted sources (so an ordinary lookup pays the local round trip),
 * the public mirrors stay behind it as the fallback, and a blank setting changes nothing.
 *
 * The URL layout is fixed by core: `{root}/amll/am-lyrics/<id>.ttml` and
 * `{root}/amlyrics/<path>`, with the trailing slash already removed.
 */
class CustomLyricsSourceRootTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the configured root is the first AMLL endpoint and the public hosts still follow`() {
        val urls = AmllTtmlClient(HttpLyricTransport(), MIRROR).urls(42L)

        assertEquals("$MIRROR/amll/am-lyrics/42.ttml", urls.first())
        assertTrue(urls.size > 1)
        assertTrue(urls.drop(1).none { it.startsWith(MIRROR) })
    }

    @Test
    fun `the configured root is the first AM-Lyrics endpoint for the index and the documents`() {
        val client = AmLyricsClient(HttpLyricTransport(), MIRROR)

        assertEquals("$MIRROR/amlyrics/index.json", client.urls("index.json").first())
        assertEquals(
            "$MIRROR/amlyrics/am-lyrics/artist/title/42.ttml",
            client.urls("am-lyrics/artist/title/42.ttml").first(),
        )
    }

    @Test
    fun `a trailing slash on the stored setting cannot produce a doubled separator`() {
        val urls = AmllTtmlClient(HttpLyricTransport(), "$MIRROR/").urls(42L)

        assertEquals("$MIRROR/amll/am-lyrics/42.ttml", urls.first())
        assertFalse(urls.first().contains("//amll"))
    }

    @Test
    fun `a blank setting keeps the shipped public order`() {
        val publicOnly = AmllTtmlClient(HttpLyricTransport(), "").urls(42L)

        assertTrue(publicOnly.isNotEmpty())
        assertFalse(publicOnly.first().startsWith(MIRROR))
        assertFalse(AmLyricsClient(HttpLyricTransport(), null).urls("index.json").first().startsWith(MIRROR))
    }

    @Test
    fun `the index cache walks the same list, so a refresh hits the mirror first`() {
        val directory = temporary.newFolder()
        val recorder = RecordingTransport()
        var now = NOW
        val cache = FileAmLyricsIndexCache(
            directory = directory,
            transport = recorder,
            // Exactly how the runtime wires it: the client owns the custom-root convention.
            indexUrls = { AmLyricsClient(recorder, MIRROR).urls("index.json") },
            executor = Executor { it.run() },
            clock = { now },
        )
        assertTrue(cache.write(INDEX_JSON, "\"etag\"", NOW - 13L * 60L * 60L * 1000L))

        // The stale copy answers at once, then the executor (inline here) refreshes.
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        now += 1L

        assertEquals("$MIRROR/amlyrics/index.json", recorder.calls.first())
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
    }

    private class RecordingTransport : LyricHttpTransport {
        val calls = mutableListOf<String>()

        override fun get(url: String): String? {
            calls += url
            return INDEX_JSON
        }

        override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? {
            calls += url
            return LyricHttpResponse(
                HttpURLConnection.HTTP_OK,
                INDEX_JSON.toByteArray(Charsets.UTF_8),
                "\"etag\"",
            )
        }
    }

    private companion object {
        const val MIRROR = "https://havenmab.cc.cd:8443/lyrics"
        const val NOW = 1_700_000_000_000L
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val INDEX_JSON = """
            {
              "version": 1,
              "layout": "artist-title-id",
              "entries": [
                {
                  "appleMusicId": 42,
                  "alternateIds": [],
                  "displayName": "Song - Artist",
                  "path": "am-lyrics/artist/title/42.ttml",
                  "enabled": true,
                  "sizeBytes": 1024,
                  "sha256": "$SHA"
                }
              ]
            }
        """
    }
}
