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
    fun `a published document keeps its per-word transliteration spans`() {
        val store = NativeLyricOverlayStore()
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="1.0" end="1.5">ishou</span><span begin="1.5" end="1.7">ni</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="1.0" end="1.7" itunes:key="L1"><span begin="1.0" end="1.5">衣装</span><span begin="1.5" end="1.7">に</span></p></div></body></tt>
        """.trimIndent()

        assertTrue(store.publishDisplayed(songId = 77L, ttml = ttml))
        // The line-lane fallback keeps the concatenation...
        assertEquals("ishouni", store.pronunciation("77", 1_000L, 1_700L, "衣装に"))
        // ...while the word getter matches the document's own timed slices.
        assertEquals(
            listOf(
                NativePronunciationSpan(1_000L, 1_500L, "ishou"),
                NativePronunciationSpan(1_500L, 1_700L, "ni"),
            ),
            store.pronunciationSpans("77", 1_000L, 1_700L, "衣装に"),
        )
        // A line-level document yields no word-level slices.
        assertTrue(
            store.pronunciationSpans("77", 3_000L, 4_000L, "君の名は").isEmpty(),
        )
    }

    @Test
    fun `a published background vocal keeps its own lane and its own key`() {
        val store = NativeLyricOverlayStore()
        // The custom document's L19: the main lyric's four words and the `x-bg`
        // group's own word are two independent span sets in the transliteration
        // track. The background must not be folded into the main lane, and the
        // main text must be the lyric only so the native line still matches.
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="1.0" end="1.4">ri</span><span begin="1.4" end="1.8">ri</span><span begin="1.8" end="2.0">boon</span><span ttm:role="x-bg"><span begin="2.1" end="2.5">(boon)</span></span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="1.0" end="2.0" itunes:key="L1"><span begin="1.0" end="1.4">リ</span><span begin="1.4" end="1.8">リ</span><span begin="1.8" end="2.0">ボーン</span><span ttm:role="x-bg"><span begin="2.1" end="2.5">(ボーン！)</span></span></p></div></body></tt>
        """.trimIndent()

        assertTrue(store.publishDisplayed(songId = 77L, ttml = ttml))
        // The line lane is the lyric's own slices; the background's `(boon)` is not
        // among them, so the render plan can never put it on the last main word.
        assertEquals(
            listOf(
                NativePronunciationSpan(1_000L, 1_400L, "ri"),
                NativePronunciationSpan(1_400L, 1_800L, "ri"),
                NativePronunciationSpan(1_800L, 2_000L, "boon"),
            ),
            store.pronunciationSpans("77", 1_000L, 2_000L, "リリボーン"),
        )
        // The line's own text is the lyric only, so the native line matches exactly.
        assertEquals(
            OverlayMatch.EXACT_TIMING,
            store.lookup("77", 1_000L, 2_000L, "リリボーン").match,
        )
        // The background lane is reachable through the line row...
        val byLine = store.lookupBackground(77L.toString(), 1_000L, 2_000L, "リリボーン")
        assertEquals(OverlayMatch.EXACT_TIMING, byLine.match)
        assertEquals(
            listOf(NativePronunciationSpan(2_100L, 2_500L, "(boon)")),
            byLine.background?.pronunciationSpans,
        )
        assertEquals(2_100L, byLine.background?.begin)
        assertEquals(2_500L, byLine.background?.end)
        // ...and, as a bounded fallback, by the `x-bg` span's own extent.
        val byBackground = store.lookupBackground(
            songId = "77",
            lineBegin = 9_999L,
            lineEnd = 9_999L,
            lineText = "unknown",
            backgroundBegin = 2_100L,
            backgroundEnd = 2_500L,
        )
        assertEquals(OverlayMatch.TIMING, byBackground.match)
        assertEquals("(boon)", byBackground.background?.pronunciationSpans?.single()?.text)
        // A line with no background never gets the main lane handed back.
        assertNull(store.lookupBackground("77", 5_000L, 6_000L, "nope").background)
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
        // Every key that could answer is ambiguous here, so the lookup reports the
        // miss instead of guessing.
        assertEquals(OverlayMatch.MISSING, store.lookup("77", 1_000L, 2_000L, "c").match)
        assertNull(store.lookup("77", 1_000L, 2_000L, "c").content)
    }

    @Test
    fun `a line kept by its begin still answers when the app's end differs`() {
        // The document's `<p end>` and the app's line end are derived
        // independently: a background vocal may end the app's line after the
        // lyric line's own `<p end>`, and an offline/online merge may re-time an
        // end. The line's begin is its identity, so it is the bounded fallback.
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字")))

        val row = store.lookup("77", 1_000L, 2_350L, "君の名は")
        assertEquals(OverlayMatch.BEGIN, row.match)
        assertEquals("你的名字", row.content?.translation)
        assertEquals("你的名字", store.translation("77", 1_000L, 2_350L, "君の名は"))
    }

    @Test
    fun `a line kept by its text still answers when both ends differ`() {
        val store = NativeLyricOverlayStore()
        store.update(
            "77",
            listOf(
                line(1_000L, 2_000L, "君の名は", translation = "你的名字"),
                line(3_000L, 4_000L, "夢のあと", translation = "梦的痕迹"),
            ),
        )

        val row = store.lookup("77", 1_050L, 2_100L, "君の名は")
        assertEquals(OverlayMatch.TEXT, row.match)
        assertEquals("你的名字", row.content?.translation)
        // A repeated line (two rows with the same text) is never guessed at.
        store.update(
            "77",
            listOf(
                line(1_000L, 2_000L, "同じ", translation = "同"),
                line(3_000L, 4_000L, "同じ", translation = "又同"),
            ),
        )
        assertEquals(OverlayMatch.MISSING, store.lookup("77", 1_050L, 2_100L, "同じ").match)
    }

    @Test
    fun `a matching key is named so the log can tell exact from fallback`() {
        val store = NativeLyricOverlayStore()
        store.update("77", listOf(line(1_000L, 2_000L, "君の名は", translation = "你的名字")))

        assertEquals(OverlayMatch.EXACT_TIMING, store.lookup("77", 1_000L, 2_000L, "君の名は").match)
        assertEquals(OverlayMatch.TIMING, store.lookup("77", 1_000L, 2_000L, "君の  名は").match)
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
