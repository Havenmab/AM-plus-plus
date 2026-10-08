package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two functions ported from HLE's `OnlineLyricTargeterPolicy.kt`.
 * They are the authority for the current/original metadata order, so the
 * blank, case-insensitive and distinct cases are asserted directly.
 */
class LyricMetadataSearchOrderTest {

    @Test
    fun `blank original metadata is never distinct`() {
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", null, null))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "", ""))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "   ", "\t"))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", null, "   "))
    }

    @Test
    fun `a distinct original title or artist triggers the retry`() {
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "Original", null))
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", null, "Original Artist"))
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "Original", "Original Artist"))
    }

    @Test
    fun `the comparison is case insensitive and trims both sides`() {
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "  song  ", "ARTIST"))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "SONG", "artist"))
        // Only one side has to differ.
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "SONG", "Other"))
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "Other", "ARTIST"))
    }

    @Test
    fun `a blank field falls back to the displayed value instead of counting as distinct`() {
        // HLE resolves a blank original field to the current one, so the pass
        // is only distinct when the non-blank field differs.
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "   ", "Artist"))
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "", "Other Artist"))
    }

    @Test
    fun `no distinct original metadata resolves to one current pass`() {
        assertEquals(
            listOf(false),
            resolveMetadataSearchOrder(
                preferOriginalMetadata = false,
                hasDistinctOriginalMetadata = false,
            ),
        )
        assertEquals(
            listOf(false),
            resolveMetadataSearchOrder(
                preferOriginalMetadata = true,
                hasDistinctOriginalMetadata = false,
            ),
        )
    }

    @Test
    fun `current metadata comes first by default and original first when preferred`() {
        assertEquals(
            listOf(false, true),
            resolveMetadataSearchOrder(
                preferOriginalMetadata = false,
                hasDistinctOriginalMetadata = true,
            ),
        )
        assertEquals(
            listOf(true, false),
            resolveMetadataSearchOrder(
                preferOriginalMetadata = true,
                hasDistinctOriginalMetadata = true,
            ),
        )
    }
}
