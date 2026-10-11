package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the timing-keyed lane overlay the native-model delivery
 * reads through. The store is the fork's port of HLE's
 * `AppleNativeOnlineTranslationStore`: one overlay for the active track,
 * sanitized on write, looked up by timing and text, and never readable across
 * tracks. It carries the online translation lane and the displayed document's own
 * romanization, which the host serves only for a supplement track.
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
    fun `the displayed document's romanization is stored beside the translation`() {
        val store = NativeLyricOverlayStore()

        // A pronunciation-only body is a real lane now: it is the custom
        // document's own romanization, which the app's model does not surface.
        assertTrue(
            store.update(
                songId = "77",
                lines = listOf(line(1_000L, 2_000L, "君の名は", roma = "Kimi no na wa")),
            ),
        )
        assertEquals("Kimi no na wa", store.pronunciation("77", 1_000L, 2_000L, "君の名は"))
        assertTrue(store.hasPronunciation("77"))
        assertNull(store.translation("77", 1_000L, 2_000L, "君の名は"))
        // A body with both lanes keeps both.
        assertTrue(
            store.update(
                songId = "77",
                lines = listOf(
                    line(1_000L, 2_000L, "君の名は", translation = "你的名字", roma = "Kimi no na wa"),
                ),
            ),
        )
        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の名は"))
        assertEquals("Kimi no na wa", store.pronunciation("77", 1_000L, 2_000L, "君の名は"))
    }

    @Test
    fun `a published displayed document carries both lanes for its own track`() {
        val store = NativeLyricOverlayStore()
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">你的名字</text></translation></translations>
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="1.0" end="2.0">Kimi no na wa</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="1.0" end="2.0" itunes:key="L1"><span begin="1.0" end="2.0">君の名は</span></p></div></body></tt>
        """.trimIndent()

        assertTrue(store.publishDisplayed(songId = 77L, ttml = ttml))
        assertEquals("你的名字", store.translation("77", 1_000L, 2_000L, "君の名は"))
        assertEquals("Kimi no na wa", store.pronunciation("77", 1_000L, 2_000L, "君の名は"))
        assertEquals("displayed", store.pronunciationSource("77"))
        // Another track can never read it.
        assertNull(store.pronunciation("88", 1_000L, 2_000L, "君の名は"))
        assertFalse(store.hasPronunciation("88"))
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
