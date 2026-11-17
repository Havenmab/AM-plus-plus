package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 (language hint) and D3 (a miss is not a resolution) as pure decisions.
 *
 * The coordinator wires these into the reflection-heavy path, so the numbers and the ordering live
 * here where a plain JVM test can pin them: the hint must break the bootstrap, the backoff must let
 * a later attempt through, and the store must keep a miss retryable instead of silently marking the
 * artist resolved.
 */
class OriginalArtistLanguagePolicyTest {

    private fun supported(value: String?): String? = when (value) {
        "ja-JP" -> "ja-JP"
        "zh-Hans" -> "zh-Hans"
        else -> null
    }

    @Test
    fun `a remembered artist language wins over every fallback`() {
        assertEquals(
            "ja-JP",
            OriginalArtistLanguagePolicy.pick("ja-JP", "zh-Hans", "ja-JP", ::supported),
        )
    }

    @Test
    fun `the cached region wins when nothing was remembered`() {
        assertEquals(
            "zh-Hans",
            OriginalArtistLanguagePolicy.pick(null, "zh-Hans", "ja-JP", ::supported),
        )
    }

    @Test
    fun `the song alias hint is the bootstrap fallback`() {
        assertEquals(
            "ja-JP",
            OriginalArtistLanguagePolicy.pick(null, null, "ja-JP", ::supported),
        )
    }

    @Test
    fun `an unsupported hint is ignored and everything may be null`() {
        assertNull(OriginalArtistLanguagePolicy.pick(null, null, "und-Latn", ::supported))
        assertNull(OriginalArtistLanguagePolicy.pick(null, null, null, ::supported))
    }

    @Test
    fun `the hint store remembers, forgets and ignores blanks`() {
        val store = OriginalLanguageHintStore()

        store.remember("1", "ja-JP")
        store.remember("2", "  ")
        store.remember("", "ja-JP")

        assertEquals("ja-JP", store.hint("1"))
        assertNull(store.hint("2"))
        assertNull(store.hint(""))

        store.forget("1")
        assertNull(store.hint("1"))
    }

    @Test
    fun `the first attempt always runs and later ones back off`() {
        assertTrue(OriginalArtistRetryPolicy.shouldRetry(0, null, now = 1_000L))
        assertFalse(OriginalArtistRetryPolicy.shouldRetry(1, 1_000L, now = 1_000L))
        assertFalse(
            OriginalArtistRetryPolicy.shouldRetry(
                1,
                1_000L,
                now = 1_000L + OriginalArtistRetryPolicy.BASE_DELAY_MS - 1,
            ),
        )
        assertTrue(
            OriginalArtistRetryPolicy.shouldRetry(
                1,
                1_000L,
                now = 1_000L + OriginalArtistRetryPolicy.BASE_DELAY_MS,
            ),
        )
        assertEquals(
            OriginalArtistRetryPolicy.MAX_DELAY_MS,
            OriginalArtistRetryPolicy.delayMillis(20),
        )
    }

    @Test
    fun `a miss is recorded, keeps retrying and never becomes resolved`() {
        val tracker = OriginalArtistRetryTracker()

        assertEquals(0, tracker.misses("1"))
        assertTrue(tracker.shouldRetry("1", now = 10_000L))

        assertEquals(1, tracker.recordMiss("1", 10_000L))
        assertFalse(tracker.shouldRetry("1", now = 10_000L))
        assertTrue(
            tracker.shouldRetry("1", now = 10_000L + OriginalArtistRetryPolicy.BASE_DELAY_MS),
        )
        assertEquals(2, tracker.recordMiss("1", 70_000L))
        assertEquals(2, tracker.misses("1"))
        assertEquals(0, tracker.misses("2"))

        tracker.forget("1")
        assertEquals(0, tracker.misses("1"))
        assertTrue(tracker.shouldRetry("1", now = 70_000L))
    }

    @Test
    fun `the store keeps a miss retryable and forgets it when the artist resolves`() {
        val store = AppleMetadataOverrideStore()

        assertTrue(store.shouldRetryOriginalArtist("1", now = 1_000L))
        assertEquals(1, store.recordOriginalArtistMiss("1", 1_000L))
        assertEquals(1, store.originalArtistMisses("1"))
        assertFalse(store.isOriginalArtistResolved("1"))
        assertFalse(store.shouldRetryOriginalArtist("1", now = 1_000L))
        assertTrue(
            store.shouldRetryOriginalArtist(
                "1",
                now = 1_000L + OriginalArtistRetryPolicy.BASE_DELAY_MS,
            ),
        )

        // A successful resolution clears the backoff and marks the artist resolved.
        store.markOriginalArtistResolved("1")
        assertTrue(store.isOriginalArtistResolved("1"))
        assertEquals(0, store.originalArtistMisses("1"))
        assertTrue(store.shouldRetryOriginalArtist("1", now = 2_000L))
    }
}
