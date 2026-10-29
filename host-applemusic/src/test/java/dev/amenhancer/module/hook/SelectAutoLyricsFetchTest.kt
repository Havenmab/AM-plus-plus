package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage of the automatic-path decision.
 *
 * The order is: fixed providers first, translation lane afterwards.
 *
 *  - **no document, unsynchronised text, or line-level timing** → the fixed providers
 *    (AMLL → Lunabeat → AM-Lyrics) are asked first, because their documents carry word-level
 *    timing and often their own translation/romanization; the four third-party search sources
 *    only fill the translation lane when nothing replaced the document;
 *  - **a word-timed Apple document** → already the finest timeline, so it is never replaced: the
 *    translation lane is filled first and the resolver is only the fallback.
 *
 * A null or throwing enrichment must never suppress a replacement, and with the toggle off the
 * path is exactly the resolver.
 */
class SelectAutoLyricsFetchTest {

    @Test
    fun `toggle off keeps the resolver path untouched`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = null,
            displayedTtml = STATIC,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertEquals("resolved", result?.ttml)
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `an unsynchronised document is replaced first and the enricher is not consulted`() {
        var resolverCalls = 0
        var enricherCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ ->
                enricherCalls += 1
                "merged"
            },
            displayedTtml = STATIC,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("online-search", "replaced")
            },
        )

        assertEquals("replaced", result?.ttml)
        assertEquals(1, resolverCalls)
        assertEquals(0, enricherCalls)
    }

    @Test
    fun `an unsynchronised document falls back to enrichment when nothing replaces it`() {
        var seenTtml: String? = null

        val result = selectAutoLyricsFetch(
            enrich = { _, ttml ->
                seenTtml = ttml
                "merged"
            },
            displayedTtml = STATIC,
            appleMusicId = 42L,
            resolverFetch = { null },
        )

        assertEquals("merged", result?.ttml)
        assertEquals(ONLINE_TRANSLATION_LYRIC_SOURCE, result?.source)
        assertEquals(STATIC, seenTtml)
    }

    @Test
    fun `a timed document is routed through the enricher and never replaced`() {
        var resolverCalls = 0
        var seenId: Long? = null
        var seenTtml: String? = null

        val result = selectAutoLyricsFetch(
            enrich = { id, ttml ->
                seenId = id
                seenTtml = ttml
                "merged"
            },
            displayedTtml = WORD_TIMED,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("online-search", "replaced")
            },
        )

        assertEquals("merged", result?.ttml)
        assertEquals(ONLINE_TRANSLATION_LYRIC_SOURCE, result?.source)
        assertEquals(42L, seenId)
        assertEquals(TIMED, seenTtml)
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `a failed enrichment on a timed document falls back to the resolver`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ -> null },
            displayedTtml = WORD_TIMED,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertEquals("resolved", result?.ttml)
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `a throwing enrichment on a timed document falls back to the resolver`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ -> throw IllegalStateException("boom") },
            displayedTtml = WORD_TIMED,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertEquals("resolved", result?.ttml)
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `a throwing enrichment on an unsynchronised document still replaces`() {
        var resolverCalls = 0
        var enricherCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ ->
                enricherCalls += 1
                throw IllegalStateException("boom")
            },
            displayedTtml = STATIC,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("online-search", "replaced")
            },
        )

        assertEquals("replaced", result?.ttml)
        assertEquals(1, resolverCalls)
        assertEquals(0, enricherCalls)
    }

    @Test
    fun `no displayed document still supplements through the resolver`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ -> "merged" },
            displayedTtml = null,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertEquals("resolved", result?.ttml)
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `a line-timed document asks the fixed providers first`() {
        var resolverCalls = 0
        var enricherCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ ->
                enricherCalls += 1
                "merged"
            },
            displayedTtml = TIMED,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "word")
            },
        )

        // A word-timed document from a fixed provider beats a line-timed Apple one, so the
        // resolver runs first and the translation lane is filled afterwards, not before.
        assertEquals("word", result?.ttml)
        assertEquals(1, resolverCalls)
        assertEquals(0, enricherCalls)
    }

    @Test
    fun `a line-timed document falls back to the translation lane when nothing replaces it`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ -> "merged" },
            displayedTtml = TIMED,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                null
            },
        )

        assertEquals("merged", result?.ttml)
        assertEquals(ONLINE_TRANSLATION_LYRIC_SOURCE, result?.source)
        assertEquals(1, resolverCalls)
    }

    private companion object {
        /** Apple published only text: no line or word carries a begin time. */
        const val STATIC =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\">" +
                "<body><div><p>hello</p></div></body></tt>"

        /** Apple published line-timed lyrics: the line carries a begin time, the words do not. */
        const val TIMED =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\">" +
                "<body><div><p begin=\"00:00:01.000\" end=\"00:00:03.000\">hello</p></div></body></tt>"

        /** Apple published word-level timing: already the finest timeline, never replaced. */
        const val WORD_TIMED = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja"><body><div><p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:02.000">hel</span><span begin="00:00:02.000" end="00:00:03.000">lo</span></p></div></body></tt>"""
    }
}
