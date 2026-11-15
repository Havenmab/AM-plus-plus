package dev.amenhancer.module.lyrics.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub fallback order, and the fact that it actually short-circuits.
 *
 * Both GitHub-hosted sources are useless when `raw.githubusercontent.com` is reset, which is
 * the normal state on some mobile networks. These tests pin the two things that make the
 * repair work: the mirrors come first, and the first endpoint that answers ends the walk.
 */
class GitHubContentEndpointsTest {

    @Test
    fun `mirrors come before the raw host and all name the same file`() {
        val urls = GitHubContentEndpoints.urls(
            owner = "amll-dev",
            repo = "amll-ttml-db",
            ref = "main",
            path = "am-lyrics/42.ttml",
        )

        assertEquals(4, urls.size)
        assertTrue("jsDelivr first: ${urls[0]}", urls[0].startsWith("https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/"))
        assertTrue("first proxy second: ${urls[1]}", urls[1].startsWith("https://gh-proxy.com/https://raw.githubusercontent.com/"))
        assertTrue("second proxy third: ${urls[2]}", urls[2].startsWith("https://ghproxy.net/https://raw.githubusercontent.com/"))
        assertTrue("raw last: ${urls[3]}", urls[3].startsWith("https://raw.githubusercontent.com/"))
        urls.forEach { url -> assertTrue(url, url.endsWith("am-lyrics/42.ttml")) }
    }

    @Test
    fun `blank parts produce no endpoints`() {
        assertTrue(GitHubContentEndpoints.urls("", "repo", "main", "p").isEmpty())
        assertTrue(GitHubContentEndpoints.urls("owner", "repo", "main", "   ").isEmpty())
        assertTrue(GitHubContentEndpoints.urls("/owner/", "/repo/", "/main/", "/p").first().contains("/owner/repo@main/p"))
    }

    @Test
    fun `amll falls back to the second endpoint when the cdn fails`() {
        val transport = ScriptedTransport(failFirst = 1)

        assertEquals(AMLL_BODY, AmllTtmlClient(transport).fetch(42L))
        assertEquals(2, transport.requested.size)
        assertTrue(transport.requested[0].contains("cdn.jsdelivr.net"))
        assertTrue(transport.requested[1].contains("gh-proxy.com"))
    }

    @Test
    fun `am lyrics index walks the same fallback`() {
        val transport = ScriptedTransport(failFirst = 1, body = INDEX_JSON)

        assertNotNull(AmLyricsClient(transport).fetchIndex())
        assertEquals(2, transport.requested.size)
        assertTrue(transport.requested[0].contains("cdn.jsdelivr.net"))
        assertTrue(transport.requested[1].contains("gh-proxy.com"))
    }

    @Test
    fun `every endpoint failing is a miss, not a throw`() {
        val transport = ScriptedTransport(failFirst = 99)

        assertNull(AmllTtmlClient(transport).fetch(42L))
        assertEquals(4, transport.requested.size)
        assertTrue(transport.requested.last().startsWith("https://raw.githubusercontent.com/"))
    }

    @Test
    fun `a self-hosted root is tried before every public mirror`() {
        val transport = ScriptedTransport(failFirst = 0)

        val amll = AmllTtmlClient(transport, customRoot = "https://lyrics.example.com/lyrics/")
        // Trailing slash in the setting, no double slash in the URL.
        assertEquals("https://lyrics.example.com/lyrics/amll/am-lyrics/42.ttml", amll.urls(42L).first())
        assertEquals(5, amll.urls(42L).size)

        val amLyrics = AmLyricsClient(transport, customRoot = "https://lyrics.example.com/lyrics")
        assertEquals(
            "https://lyrics.example.com/lyrics/amlyrics/index.json",
            amLyrics.urls("index.json").first(),
        )
    }

    @Test
    fun `a blank custom root keeps the public mirrors unchanged`() {
        assertEquals(4, AmllTtmlClient(ScriptedTransport(0), customRoot = "  ").urls(42L).size)
        assertEquals(4, AmllTtmlClient(ScriptedTransport(0)).urls(42L).size)
    }

    @Test
    fun `the chain budget stops before the next source is attempted`() {
        var attempts = 0
        val slow = AutoLyricsSource("slow") {
            attempts += 1
            Thread.sleep(120)
            null
        }
        val second = AutoLyricsSource("second") {
            attempts += 1
            "<tt/>"
        }
        val resolver = AutoLyricsSourceResolver(listOf(slow, second))

        val started = System.nanoTime()
        val result = resolver.fetch(42L, budgetMs = 50)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // The budget is spent inside the slow source, so the second one is never asked.
        assertNull(result)
        assertEquals(1, attempts)
        assertTrue("walk bounded: ${elapsedMs}ms", elapsedMs < 1_000)
    }

    /** Answers `null` for the first [failFirst] requests, then [body]. */
    private class ScriptedTransport(
        private val failFirst: Int,
        private val body: String = AMLL_BODY,
    ) : LyricHttpTransport {
        val requested = mutableListOf<String>()

        override fun get(url: String): String? {
            requested += url
            return if (requested.size <= failFirst) null else body
        }
    }

    private companion object {
        const val AMLL_BODY = "<tt>lyrics</tt>"
        const val INDEX_JSON = """{"version":1,"layout":"artist-title-id","entries":[]}"""
    }
}
