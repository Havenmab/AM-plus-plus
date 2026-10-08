package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the timing-keyed overlay HLE's native-model delivery
 * reads through. The store is the fork's port of HLE's
 * `AppleNativeOnlineTranslationStore`: one overlay for the active track,
 * sanitized on write, looked up by timing and text, and never readable across
 * tracks.
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
    fun `stores the sanitized online lanes keyed by timing and text`() {
        val store = NativeLyricOverlayStore()

        assertTrue(
            store.update(
                songId = "77",
                lines = listOf(
                    line(1_000L, 2_000L, "君の名は", translation = "你的名字", roma = "Kimi no na wa"),
                ),
                translationSource = "QM",
                pronunciationSource = "QM",
            ),
        )
        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の名は"))
        assertEquals("Kimi no na wa", store.pronunciation("77", 1_000L, 2_000L, "君の名は"))
        assertTrue(store.hasTranslation("77"))
        assertTrue(store.hasPronunciation("77"))
        assertEquals("QM", store.translationSource("77"))
        assertEquals("QM", store.pronunciationSource("77"))
        assertEquals("77", store.currentSongId())
    }

    @Test
    fun `a timing with a single entry still answers when Apple re-normalizes the text`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa")))

        assertEquals("Kimi no na wa", store.pronunciation("77", 1_000L, 2_000L, "君の  名は"))
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
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa")))

        assertNull(store.pronunciation("88", 1_000L, 2_000L, "君の名は"))
        assertFalse(store.hasPronunciation("88"))
        assertTrue(store.hasPronunciation("77"))
    }

    @Test
    fun `a pronunciation the policy rejects is not advertised`() {
        val store = NativeLyricOverlayStore()
        // The echo and the kana candidate are both dropped by RomanizationPolicy.
        store.update(
            "77",
            listOf(
                line(1_000L, 2_000L, "君の名は", roma = "君の名は"),
                line(2_000L, 3_000L, "ありがとう", roma = "ありがとう"),
            ),
        )

        assertFalse(store.hasPronunciation("77"))
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
        assertTrue(store.update("77", listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa"))))
        val revision = store.revision()

        assertFalse(store.update("", listOf(line(1_000L, 2_000L, "x", translation = "y"))))
        assertFalse(store.update("77", listOf(line(1_000L, 2_000L, "君の名は"))))
        assertEquals(revision, store.revision())
        assertEquals("Kimi no na wa", store.pronunciation("77", 1_000L, 2_000L, "君の名は"))
    }

    @Test
    fun `an identical update does not advance the revision`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa")))
        val revision = store.revision()

        assertFalse(store.update("77", listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa"))))
        assertEquals(revision, store.revision())
    }

    @Test
    fun `clear only drops the matching track`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa")))

        assertFalse(store.clear("88"))
        assertTrue(store.hasPronunciation("77"))
        assertTrue(store.clear("77"))
        assertNull(store.currentSongId())
    }

    @Test
    fun `text normalization collapses whitespace like HLE`() {
        assertEquals("a b", NativeLyricOverlayStore.normalizeText("  a \n b "))
        assertEquals("", NativeLyricOverlayStore.normalizeText(null))
    }
}
