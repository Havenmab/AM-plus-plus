package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage of the automatic-path decision.
 *
 * The order depends on what Apple is already showing:
 *
 *  - **no document, or only unsynchronised text** (it does not scroll with playback) → the
 *    replacement path runs first, because a third-party document carrying a real timeline is a
 *    strict improvement, and enrichment is only the fallback;
 *  - **a document that already carries line or word timing** → never replaced by a search source;
 *    only a missing translation lane is added, and the resolver is consulted only when enrichment
 *    yields nothing (it may still reach the bundled providers, which are allowed to replace).
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
            displayedTtml = TIMED,
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
            displayedTtml = TIMED,
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
            displayedTtml = TIMED,
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

    private companion object {
        /** Apple published only text: no line or word carries a begin time. */
        const val STATIC =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\">" +
                "<body><div><p>hello</p></div></body></tt>"

        /** Apple published synchronised lyrics: the line carries a begin time. */
        const val TIMED =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\">" +
                "<body><div><p begin=\"00:00:01.000\" end=\"00:00:03.000\">hello</p></div></body></tt>"
    }
}
