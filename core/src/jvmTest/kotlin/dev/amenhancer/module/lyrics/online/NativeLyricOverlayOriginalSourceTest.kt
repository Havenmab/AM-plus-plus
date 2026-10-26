package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one thing the overlay store cannot derive: where the displayed original
 * document came from. The store serves the translation/pronunciation lanes of a
 * document this module wrote, so the publisher hands the provider name in and the
 * lyrics-source sheet can show the real platform instead of "已写入歌词".
 */
class NativeLyricOverlayOriginalSourceTest {

    @Test
    fun `the displayed original source round trips for its own song only`() {
        val store = NativeLyricOverlayStore()

        assertTrue(store.update(songId = SONG_ID, lines = listOf(line()), originalSource = "amll-ttml-db"))

        assertEquals("amll-ttml-db", store.originalSource(SONG_ID))
        assertNull(store.originalSource("43"))
        assertNull(store.originalSource(null))
        assertNull(NativeLyricOverlayStore().originalSource(SONG_ID))
    }

    @Test
    fun `an unnamed source stays unknown`() {
        val store = NativeLyricOverlayStore()

        assertTrue(store.update(songId = SONG_ID, lines = listOf(line())))

        assertNull(store.originalSource(SONG_ID))
    }

    @Test
    fun `a later publication replaces the recorded source`() {
        val store = NativeLyricOverlayStore()

        store.update(songId = SONG_ID, lines = listOf(line()), originalSource = "kuwo")
        store.update(songId = SONG_ID, lines = listOf(line("另一行")), originalSource = "qq")

        assertEquals("qq", store.originalSource(SONG_ID))
    }

    @Test
    fun `publishDisplayed forwards the source`() {
        val store = NativeLyricOverlayStore()

        assertTrue(store.publishDisplayed(42L, TRANSLATED_TTML, originalSource = "am-lyrics"))

        assertEquals("am-lyrics", store.originalSource("42"))
    }

    private fun line(text: String = "夜に駆ける"): NativeLyricLine = NativeLyricLine(
        begin = 0L,
        end = 1_000L,
        text = text,
        translation = "夜跑",
    )

    private companion object {
        const val SONG_ID = "42"

        /** One Apple-shaped document carrying a translation lane, so the store accepts it. */
        val TRANSLATED_TTML =
            """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Line" xml:lang="ja">""" +
                """<head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">""" +
                """<translations><translation type="subtitle" xml:lang="zh-Hans">""" +
                """<text for="L1">夜跑</text></translation></translations>""" +
                "</iTunesMetadata></metadata></head>" +
                """<body><div><p begin="1.000" end="2.000" itunes:key="L1">夜に駆ける</p>""" +
                "</div></body></tt>"
    }
}
