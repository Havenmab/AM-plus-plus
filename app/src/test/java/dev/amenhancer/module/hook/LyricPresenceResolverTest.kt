package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.FileLyricPresenceCache
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.lyrics.source.LyricPresence
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The presence index at the resolver seam - the only place where the difference between its two
 * negative answers is observable:
 *
 *  - **unknown** (null: nothing downloaded yet, or no mirror at all) must leave the chain exactly
 *    as it was, asking every bundled repository in order;
 *  - **absent** (empty: the index answered and nothing carries the track) must skip every
 *    bundled repository, so the translation lane starts immediately.
 *
 * The search lane has no presence id and must keep running in both cases; gating it would trade
 * a stack of timeouts for a missing translation source.
 */
class LyricPresenceResolverTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a cold cache answers unknown so the resolver still tries every repository`() {
        val cache = cache(temporary.newFolder())
        assertNull(cache.sourcesFor(42L))

        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(sources(calls, hit = "am-lyrics"), presence = cache)

        assertEquals(AutoLyricsCandidate("am-lyrics", WORD_TTML), resolver.fetch(42L))
        assertEquals(listOf("amll", "lunabeat", "am-lyrics"), calls)
    }

    @Test
    fun `a parsed index makes an absent id skip every bundled source`() {
        val cache = cache(temporary.newFolder())
        assertTrue(cache.write("""{"42":"al"}""", "\"etag\"", NOW))

        val calls = mutableListOf<String>()
        // Every source misses, so the test observes exactly which ones were asked.
        val resolver = AutoLyricsSourceResolver(sources(calls, hit = null), presence = cache)

        // The index lists 42 for AMLL and AM-Lyrics: Lunabeat is not asked at all.
        assertNull(resolver.fetch(42L))
        assertEquals(listOf("amll", "am-lyrics", "search"), calls)

        // An ID the index does not name: no bundled repository is asked, so the chain does not
        // spend a stack of timeouts on a song nobody carries.
        calls.clear()
        assertNull(resolver.fetch(99L))
        assertEquals(listOf("search"), calls)
    }

    @Test
    fun `a cache without a mirror behaves like no presence index at all`() {
        val cache = FileLyricPresenceCache(
            directory = temporary.newFolder(),
            transport = NoNetworkTransport,
            customRoot = "",
            executor = Executor { },
            clock = { NOW },
        )
        assertFalse(cache.enabled)
        assertNull(cache.sourcesFor(42L))

        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(sources(calls, hit = null), presence = cache)

        assertNull(resolver.fetch(42L))
        assertEquals(listOf("amll", "lunabeat", "am-lyrics", "search"), calls)
    }

    private fun cache(directory: File): FileLyricPresenceCache = FileLyricPresenceCache(
        directory = directory,
        transport = NoNetworkTransport,
        customRoot = MIRROR,
        executor = Executor { },
        clock = { NOW },
    )

    /** The fixed chain plus the search lane; [hit] is the one source that carries the track. */
    private fun sources(calls: MutableList<String>, hit: String?): List<AutoLyricsSource> = listOf(
        source("amll", LyricPresence.AMLL, calls, hit),
        source("lunabeat", LyricPresence.LUNABEAT, calls, hit),
        source("am-lyrics", LyricPresence.AM_LYRICS, calls, hit),
        // A search provider: no presence id, so it is never gated by the index.
        source("search", null, calls, hit),
    )

    private fun source(
        name: String,
        presenceId: String?,
        calls: MutableList<String>,
        hit: String?,
    ): AutoLyricsSource = AutoLyricsSource(name, presenceId = presenceId) {
        calls += name
        if (name == hit) WORD_TTML else null
    }

    /** The resolver must never make the cache fetch: the tests drive refreshes explicitly. */
    private object NoNetworkTransport : LyricHttpTransport {
        override fun get(url: String): String? = error("the resolver must not fetch the presence index")

        override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? =
            error("the resolver must not fetch the presence index")
    }

    private companion object {
        const val MIRROR = "https://havenmab.cc.cd:8443/lyrics"
        const val NOW = 1_700_000_000_000L
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
    }
}
