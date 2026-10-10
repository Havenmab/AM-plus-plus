package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the timing-keyed translation overlay the native-model
 * delivery reads through. The store is the fork's port of HLE's
 * `AppleNativeOnlineTranslationStore`, reduced to the translation half under the
 * Apple-only pronunciation policy: one overlay for the active track, sanitized
 * on write, looked up by timing and text, never readable across tracks, and
 * never carrying a provider's romanization.
 */
class NativeLyricOverlayStoreTest {

    private fun line(
        begin: Long,
        end: Long,
        text: String,
        translation: String? = null,
        roma: String? = null,
    ) = NativeLyricLine(
        begin = begin,
        end = end,
        text = text,
        translation = translation,
        roma = roma,
    )

    @Test
    fun `stores the sanitized translation lane keyed by timing and text`() {
        val store = NativeLyricOverlayStore()

        assertTrue(
            store.update(
                songId = "77",
                lines = listOf(
                    line(1_000L, 2_000L, "君の名は", translation = "你的名字", roma = "Kimi no na wa"),
                ),
                translationSource = "QM",
            ),
        )
        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の名は"))
        assertTrue(store.hasTranslation("77"))
        assertEquals("QM", store.translationSource("77"))
        assertEquals("77", store.currentSongId())
    }

    @Test
    fun `a provider romanization is never stored`() {
        val store = NativeLyricOverlayStore()

        // A pronunciation-only body has no lane to store at all.
        assertFalse(
            store.update(
                songId = "77",
                lines = listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa")),
            ),
        )
        assertNull(store.currentSongId())
        // A body with both lanes keeps only the translation.
        assertTrue(
            store.update(
                songId = "77",
                lines = listOf(
                    line(1_000L, 2_000L, "君の名は", translation = "你的名字", roma = "Kimi no na wa"),
                ),
            ),
        )
        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の名は"))
    }

    @Test
    fun `a timing with a single entry still answers when Apple re-normalizes the text`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字")))

        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の  名は"))
    }

    @Test
    fun `two lines on one timing stay ambiguous and the exact text still wins`() {
        val store = NativeLyricOverlayStore()
        store.update(
            "77",
            listOf(
                line(1_000L, 2_000L, "a", translation = "甲"),
                line(1_000L, 2_000L, "b", translation = "乙"),
            ),
        )

        assertNull(store.translation("77", 1_000L, 2_000L, "c"))
        assertEquals("甲", store.translation("77", 1_000L, 2_000L, "a"))
        assertEquals("乙", store.translation("77", 1_000L, 2_000L, "b"))
    }

    @Test
    fun `another track can never read the overlay`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字")))

        assertNull(store.translation("88", 1_000L, 2_000L, "君の名は"))
        assertFalse(store.hasTranslation("88"))
        assertTrue(store.hasTranslation("77"))
    }

    @Test
    fun `a slash placeholder translation leaves no lane and no overlay`() {
        val store = NativeLyricOverlayStore()

        assertFalse(store.update("77", listOf(line(1_000L, 2_000L, "Hello", translation = "//"))))
        assertNull(store.translation("77", 1_000L, 2_000L, "Hello"))
        assertFalse(store.hasTranslation("77"))
    }

    @Test
    fun `a blank id and an empty body are rejected without clearing the previous overlay`() {
        val store = NativeLyricOverlayStore()
        assertTrue(store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字"))))
        val revision = store.revision()

        assertFalse(store.update("", listOf(line(1_000L, 2_000L, "x", translation = "y"))))
        assertFalse(store.update("77", listOf(line(1_000L, 2_000L, "君の名は"))))
        assertEquals(revision, store.revision())
        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の名は"))
    }

    @Test
    fun `an identical update does not advance the revision`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字")))
        val revision = store.revision()

        assertFalse(store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字"))))
        assertEquals(revision, store.revision())
    }

    @Test
    fun `clear only drops the matching track`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字")))

        assertFalse(store.clear("88"))
        assertTrue(store.hasTranslation("77"))
        assertTrue(store.clear("77"))
        assertNull(store.currentSongId())
    }

    @Test
    fun `text normalization collapses whitespace like HLE`() {
        assertEquals("a b", NativeLyricOverlayStore.normalizeText("  a \n b "))
        assertEquals("", NativeLyricOverlayStore.normalizeText(null))
    }
}
