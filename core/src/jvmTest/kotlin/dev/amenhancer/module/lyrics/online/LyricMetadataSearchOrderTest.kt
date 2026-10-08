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
    fun `the predicate is HLE's: a difference in either field counts and blanks are not resolved here`() {
        // HLE applies its blank fallback where the pass metadata is built, not inside this
        // predicate, so a blank original title still counts as a difference against a
        // non-blank displayed title.  The consequence is one extra pass whose keyword ends up
        // equal to the displayed one; the first-pass-miss gate is what keeps that duplicate
        // off the ordinary path.  "Nothing to retry with" and genuine equality are the only
        // false cases.
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "   ", "Artist"))
        assertTrue(shouldRetryWithOriginalMetadata("Song", "Artist", "", "Other Artist"))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", null, null))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "  ", "   "))
        assertFalse(shouldRetryWithOriginalMetadata("Song", "Artist", "song", "artist"))
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
