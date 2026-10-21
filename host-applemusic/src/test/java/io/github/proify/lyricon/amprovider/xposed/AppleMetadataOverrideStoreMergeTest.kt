package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the no-op short circuits ported from HLE v8.2.1 (`1a665f69`).
 *
 * These merges run once per queue-row registration and once per resolved id:
 * - a complete account title/artist no longer allocates a new [AccountMetadata]
 *   or replaces the map entry;
 * - an already-contained lookup-id/artist-key set no longer copies and unions;
 * - the associated-artist revision only moves on a real change, so the surface
 *   runtime can skip re-expanding an unchanged scope.
 */
class AppleMetadataOverrideStoreMergeTest {

    private val store = AppleMetadataOverrideStore()

    @Test
    fun `merging into a complete account entry returns the stored instance`() {
        val first = store.mergeAccountMetadata("1", AccountMetadata("Title", "Artist"))
        val second = store.mergeAccountMetadata("1", AccountMetadata("Title", "Artist"))

        assertSame(first, second)
    }

    @Test
    fun `merging still fills a missing field`() {
        store.mergeAccountMetadata("2", AccountMetadata(title = "Title", artist = null))

        val merged = store.mergeAccountMetadata("2", AccountMetadata("Other", "Artist"))

        assertEquals("Title", merged.title)
        assertEquals("Artist", merged.artist)
    }

    @Test
    fun `an already-contained lookup id set is left untouched`() {
        store.mergeLookupIds("3", listOf("id:1", "id:2"))
        val stored = store.lookupIds("3")

        store.mergeLookupIds("3", listOf("id:1"))

        assertSame(stored, store.lookupIds("3"))
    }

    @Test
    fun `a new lookup id still unions in`() {
        store.mergeLookupIds("4", listOf("id:1"))

        store.mergeLookupIds("4", listOf("id:2"))

        assertEquals(setOf("id:1", "id:2"), store.lookupIds("4"))
    }

    @Test
    fun `an already-contained artist key set is left untouched`() {
        store.mergeArtistKeys("5", listOf("name:a", "name:b"))
        val stored = store.artistKeys("5")

        store.mergeArtistKeys("5", listOf("name:a"))

        assertSame(stored, store.artistKeys("5"))
    }

    @Test
    fun `the associated artist revision moves only on a change`() {
        val before = store.associatedArtistIdsRevision()
        assertTrue(store.mergeAssociatedArtistIds("6", listOf("100")))
        val afterFirst = store.associatedArtistIdsRevision()
        assertNotEquals(before, afterFirst)

        // Same list again: no change, no revision move.
        assertTrue(!store.mergeAssociatedArtistIds("6", listOf("100")))
        assertEquals(afterFirst, store.associatedArtistIdsRevision())

        assertTrue(store.mergeAssociatedArtistIds("6", listOf("200")))
        assertNotEquals(afterFirst, store.associatedArtistIdsRevision())
    }
}
