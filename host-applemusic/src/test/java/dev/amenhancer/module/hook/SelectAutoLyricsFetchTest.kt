package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage of the automatic-path decision introduced for translation
 * enrichment: with the toggle off (no enricher) the fixed resolver runs exactly
 * as before; with it on every lookup is routed through the enricher and a null
 * or throwing result leaves the displayed document untouched instead of falling
 * back to a translation-free replacement.
 */
class SelectAutoLyricsFetchTest {

    @Test
    fun `toggle off keeps the resolver path untouched`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = null,
            displayedTtml = RAW,
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
    fun `toggle on routes a displayed document through the enricher only`() {
        var resolverCalls = 0
        var seenId: Long? = null
        var seenTtml: String? = null

        val result = selectAutoLyricsFetch(
            enrich = { id, ttml ->
                seenId = id
                seenTtml = ttml
                "merged"
            },
            displayedTtml = RAW,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertEquals("merged", result?.ttml)
        assertEquals(ONLINE_TRANSLATION_LYRIC_SOURCE, result?.source)
        assertEquals(42L, seenId)
        assertEquals(RAW, seenTtml)
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `a failed enrichment leaves the document untouched`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ -> null },
            displayedTtml = RAW,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertNull(result)
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `a throwing enrichment never escapes`() {
        var resolverCalls = 0

        val result = selectAutoLyricsFetch(
            enrich = { _, _ -> throw IllegalStateException("boom") },
            displayedTtml = RAW,
            appleMusicId = 42L,
            resolverFetch = {
                resolverCalls += 1
                AutoLyricsCandidate("amll-ttml-db", "resolved")
            },
        )

        assertNull(result)
        assertEquals(0, resolverCalls)
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
        const val RAW =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\">" +
                "<body><div><p>hello</p></div></body></tt>"
    }
}
