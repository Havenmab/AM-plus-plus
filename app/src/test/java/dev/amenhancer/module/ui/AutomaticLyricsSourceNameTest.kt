package dev.amenhancer.module.ui

import dev.amenhancer.module.model.CustomLyricsSources
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the row labels of the automatic order: schema 29 folds the four search providers into
 * it, so each of them must be named by the online section's labels instead of the manual
 * fallback that used to swallow every id it did not know.
 */
class AutomaticLyricsSourceNameTest {

    private fun name(source: String, locale: Locale = Locale.SIMPLIFIED_CHINESE): String =
        automaticLyricsSourceName(source) { key -> key.text(locale = locale) }

    @Test
    fun `the four search providers are named instead of reading as manual TTML`() {
        assertEquals("网易云音乐", name(CustomLyricsSources.NETEASE))
        assertEquals("QQ 音乐", name(CustomLyricsSources.QQ))
        assertEquals("酷我音乐", name(CustomLyricsSources.KUWO))
        assertEquals("酷狗音乐", name(CustomLyricsSources.KUGOU))
    }

    @Test
    fun `the search labels follow the host locale`() {
        assertEquals("NetEase Cloud Music", name(CustomLyricsSources.NETEASE, Locale.US))
        assertEquals("QQ Music", name(CustomLyricsSources.QQ, Locale.US))
        assertEquals("Kuwo Music", name(CustomLyricsSources.KUWO, Locale.US))
        assertEquals("Kugou Music", name(CustomLyricsSources.KUGOU, Locale.US))
    }

    @Test
    fun `the fixed repositories keep their own labels`() {
        assertEquals("AMLL", name(CustomLyricsSources.AMLL))
        assertEquals("Lunabeat", name(CustomLyricsSources.LUNABEAT))
        assertEquals("AM-Lyrics", name(CustomLyricsSources.AM_LYRICS))
        assertEquals("自动缓存", name(CustomLyricsSources.AUTO_CACHE))
        assertEquals("AM 原生", name(CustomLyricsSources.AM_NATIVE))
    }

    @Test
    fun `manual and unknown ids keep the manual fallback`() {
        assertEquals("手动 TTML", name(CustomLyricsSources.MANUAL))
        assertEquals("手动 TTML", name("some-future-source"))
    }
}
