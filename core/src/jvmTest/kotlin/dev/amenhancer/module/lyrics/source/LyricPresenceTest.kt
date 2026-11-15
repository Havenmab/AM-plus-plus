package dev.amenhancer.module.lyrics.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presence gate: when the index answers, bundled repositories it does not name are
 * skipped **without a request**, which is the whole point — a song nobody carries must not
 * walk every endpoint of every source before the translation lane is attempted.
 */
class LyricPresenceTest {

    @Test
    fun `an absent id skips every bundled repository but still runs the search source`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll", presenceId = LyricPresence.AMLL) { calls += "amll"; null },
                AutoLyricsSource("lunabeat", presenceId = LyricPresence.LUNABEAT) { calls += "lunabeat"; null },
                AutoLyricsSource("am-lyrics", presenceId = LyricPresence.AM_LYRICS) { calls += "am-lyrics"; null },
                AutoLyricsSource("online-search") { calls += "search"; null },
            ),
            presence = LyricPresence.indexOf(emptyMap()),
        )

        assertNull(resolver.fetch(42L))
        // Search sources are the translation lane: never gated by the repository index.
        assertEquals(listOf("search"), calls)
    }

    @Test
    fun `a single-source hit asks only that repository`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll", presenceId = LyricPresence.AMLL) { calls += "amll"; null },
                AutoLyricsSource("lunabeat", presenceId = LyricPresence.LUNABEAT) { calls += "lunabeat"; null },
                AutoLyricsSource("am-lyrics", presenceId = LyricPresence.AM_LYRICS) { calls += "am-lyrics"; null },
            ),
            presence = LyricPresence.indexOf(mapOf(42L to setOf(LyricPresence.AM_LYRICS))),
        )

        assertNull(resolver.fetch(42L))
        assertEquals(listOf("am-lyrics"), calls)
    }

    @Test
    fun `an unknown index keeps the old try-everything behaviour`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll", presenceId = LyricPresence.AMLL) { calls += "amll"; null },
                AutoLyricsSource("am-lyrics", presenceId = LyricPresence.AM_LYRICS) { calls += "am-lyrics"; null },
            ),
            presence = null,
        )

        assertNull(resolver.fetch(42L))
        assertEquals(listOf("amll", "am-lyrics"), calls)
    }

    @Test
    fun `the index parses the compact payload and prefers what it names`() {
        val parsed = LyricPresence.parse("""{"42":"a","43":"al","nope":"a","44":""}""")

        assertEquals(mapOf(42L to setOf("a"), 43L to setOf("a", "l")), parsed)

        val index = LyricPresence.indexOf(requireNotNull(parsed))
        assertEquals(setOf("a", "l"), index.sourcesFor(43L))
        // Not listed is an answer, not "unknown": the repositories must be skipped.
        assertEquals(emptySet<String>(), index.sourcesFor(999L))
    }

    @Test
    fun `a payload that is not the expected shape is unknown rather than empty`() {
        assertNull(LyricPresence.parse("not json"))
        assertNull(LyricPresence.parse("{}"))
        assertNull(LyricPresence.parse("""["a"]"""))
        assertTrue(LyricPresence.parse("""{"42":"a"}""")!!.isNotEmpty())
    }
}
