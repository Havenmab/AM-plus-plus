package io.github.proify.lyricon.amprovider.xposed.metadata

import io.github.proify.lyricon.amprovider.xposed.MetadataFieldKey
import io.github.proify.lyricon.amprovider.xposed.MetadataFieldWriteMemo
import io.github.proify.lyricon.amprovider.xposed.MetadataHostField
import io.github.proify.lyricon.amprovider.xposed.decideMetadataHostFieldWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy coverage for the host-value write skip.
 *
 * Device logs showed the same media id re-applied ~19 times on the home page.  The host allocates
 * a fresh PlaybackItem / library entity per bind, so the old instance-keyed guards never fired and
 * every walk re-wrote the same text and re-ran its change notification.  These tests pin the
 * read-before-write decision and the bounded, media-id-keyed bookkeeping behind it.
 */
class AppleMetadataHostWritePolicyTest {
    // ------------------------------------------------------- read-before-write decisions

    @Test
    fun `value already equal skips both the write and its notification`() {
        val decision = decideMetadataHostFieldWrite(
            currentValue = "translated title",
            targetValue = "translated title",
            lastAppliedValue = null,
        )
        assertFalse(decision.write)
        assertFalse(decision.notify)
    }

    @Test
    fun `value differs writes and notifies`() {
        val decision = decideMetadataHostFieldWrite(
            currentValue = "original title",
            targetValue = "translated title",
            lastAppliedValue = null,
        )
        assertTrue(decision.write)
        assertTrue(decision.notify)
    }

    @Test
    fun `missing current value writes and notifies`() {
        val decision = decideMetadataHostFieldWrite(
            currentValue = null,
            targetValue = "translated title",
            lastAppliedValue = null,
        )
        assertTrue(decision.write)
        assertTrue(decision.notify)
    }

    @Test
    fun `blank current value writes and notifies`() {
        val decision = decideMetadataHostFieldWrite(
            currentValue = "   ",
            targetValue = "translated title",
            lastAppliedValue = null,
        )
        assertTrue(decision.write)
        assertTrue(decision.notify)
    }

    @Test
    fun `blank target is never written`() {
        assertFalse(
            decideMetadataHostFieldWrite(
                currentValue = "original title",
                targetValue = "   ",
                lastAppliedValue = null,
            ).write,
        )
        assertFalse(
            decideMetadataHostFieldWrite(
                currentValue = null,
                targetValue = null,
                lastAppliedValue = null,
            ).write,
        )
    }

    // --------------------------------------------- fresh instance / changed alias behaviour

    @Test
    fun `fresh host instance that re-reads the original is skipped when the memo holds the target`() {
        // Apple built a new object per bind: the field reads the original again, but this media id
        // and field already carry the target, so the redundant write and notification are skipped.
        val decision = decideMetadataHostFieldWrite(
            currentValue = "original title",
            targetValue = "translated title",
            lastAppliedValue = "translated title",
        )
        assertFalse(decision.write)
        assertFalse(decision.notify)
    }

    @Test
    fun `a genuinely changed alias is written even when the memo holds the previous target`() {
        val decision = decideMetadataHostFieldWrite(
            currentValue = "previous translation",
            targetValue = "new translation",
            lastAppliedValue = "previous translation",
        )
        assertTrue(decision.write)
        assertTrue(decision.notify)
    }

    @Test
    fun `a fresh instance with a blank field is restored even when the memo matches`() {
        val decision = decideMetadataHostFieldWrite(
            currentValue = null,
            targetValue = "translated title",
            lastAppliedValue = "translated title",
        )
        assertTrue(decision.write)
    }

    // ------------------------------------------------------------- bounded bookkeeping

    @Test
    fun `memo returns the recorded value per media id and field`() {
        val memo = MetadataFieldWriteMemo(maxEntries = 8)
        memo.record(MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_TITLE), "a")
        assertEquals("a", memo.lastApplied(MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_TITLE)))
        assertNull(memo.lastApplied(MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_ARTIST)))
        assertNull(memo.lastApplied(MetadataFieldKey("202", MetadataHostField.PLAYBACK_ITEM_TITLE)))
        assertEquals(1, memo.size())
    }

    @Test
    fun `memo evicts the least recently used entry at capacity`() {
        val memo = MetadataFieldWriteMemo(maxEntries = 2)
        val first = MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_TITLE)
        val second = MetadataFieldKey("102", MetadataHostField.PLAYBACK_ITEM_TITLE)
        val third = MetadataFieldKey("103", MetadataHostField.PLAYBACK_ITEM_TITLE)
        memo.record(first, "a")
        memo.record(second, "b")
        // Refresh `first` so `second` becomes the least recently used entry.
        assertEquals("a", memo.lastApplied(first))
        memo.record(third, "c")
        assertEquals(2, memo.size())
        assertNull(memo.lastApplied(second))
        assertEquals("a", memo.lastApplied(first))
        assertEquals("c", memo.lastApplied(third))
    }

    @Test
    fun `memo never exceeds its capacity`() {
        val memo = MetadataFieldWriteMemo(maxEntries = 3)
        repeat(100) { index ->
            memo.record(
                MetadataFieldKey("$index", MetadataHostField.LIBRARY_ENTITY_NAME),
                "value-$index",
            )
        }
        assertEquals(3, memo.size())
        assertEquals("value-99", memo.lastApplied(MetadataFieldKey("99", MetadataHostField.LIBRARY_ENTITY_NAME)))
    }

    @Test
    fun `updating an existing key does not grow the memo`() {
        val memo = MetadataFieldWriteMemo(maxEntries = 2)
        val key = MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_ALBUM)
        memo.record(key, "first")
        memo.record(key, "second")
        assertEquals(1, memo.size())
        assertEquals("second", memo.lastApplied(key))
    }

    @Test
    fun `memo clear drops every recorded value`() {
        val memo = MetadataFieldWriteMemo(maxEntries = 2)
        memo.record(MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_TITLE), "a")
        memo.clear()
        assertEquals(0, memo.size())
        assertNull(memo.lastApplied(MetadataFieldKey("101", MetadataHostField.PLAYBACK_ITEM_TITLE)))
    }

    @Test
    fun `memo default capacity stays bounded`() {
        val memo = MetadataFieldWriteMemo()
        repeat(MetadataFieldWriteMemo.DEFAULT_MAX_ENTRIES + 16) { index ->
            memo.record(
                MetadataFieldKey("$index", MetadataHostField.LIBRARY_ENTITY_ARTIST),
                "artist-$index",
            )
        }
        assertEquals(MetadataFieldWriteMemo.DEFAULT_MAX_ENTRIES, memo.size())
    }
}
