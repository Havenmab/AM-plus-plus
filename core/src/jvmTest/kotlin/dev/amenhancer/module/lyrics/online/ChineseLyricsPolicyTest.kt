package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from HLE's `common.lyric.ChineseLyricsPolicyTest` at `adead6d`.
 * Assertions are unchanged; only the fixture types were adapted.
 */
class ChineseLyricsPolicyTest {

    @Test
    fun `fully chinese lyrics are detected`() {
        val song = NativeLyricDocument(
            lyrics = listOf(
                NativeLyricLine(text = "感谢你曾来过"),
                NativeLyricLine(text = "我早已明白了"),
            ),
        )
        assertTrue(ChineseLyricsPolicy.isFullyChinese(song))
    }

    @Test
    fun `english interjections do not break chinese detection`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "whoa"),
                    NativeLyricLine(text = "oh 感谢你曾来过"),
                    NativeLyricLine(text = "ayy 我早已明白了"),
                    NativeLyricLine(text = "yeah~ 想你"),
                    NativeLyricLine(text = "oh!"),
                ),
            ),
        )
    }

    @Test
    fun `symbol connected interjections are stripped`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "whoa-oh-oh"),
                    NativeLyricLine(text = "oh-oh 想见你"),
                    NativeLyricLine(text = "ayy-ayy, yeah-yeah 感谢你曾来过"),
                ),
            ),
        )
    }

    @Test
    fun `interjections attached to chinese text are stripped`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "oh我想你"),
                    NativeLyricLine(text = "感谢你曾来过whoa"),
                    NativeLyricLine(text = "yeah~想见你"),
                ),
            ),
        )
    }

    @Test
    fun `elongated interjections are stripped`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "oooh"),
                    NativeLyricLine(text = "ayyy 想你"),
                    NativeLyricLine(text = "yeahh"),
                    NativeLyricLine(text = "hahaha 感谢你曾来过"),
                    NativeLyricLine(text = "lalala"),
                ),
            ),
        )
    }

    @Test
    fun `pure punctuation and digit lines are neutral`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "感谢你曾来过"),
                    NativeLyricLine(text = "……"),
                    NativeLyricLine(text = "1988"),
                ),
            ),
        )
    }

    @Test
    fun `real english words still block chinese detection`() {
        assertFalse(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "感谢你曾来过"),
                    NativeLyricLine(text = "Baby 我早已明白了"),
                ),
            ),
        )
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("thank you 想你"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("no no no"))
    }

    @Test
    fun `non han scripts block chinese detection`() {
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("君の名は"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("사랑해"))
    }

    @Test
    fun `few credit lines do not break chinese detection`() {
        val lines = buildList {
            add(NativeLyricLine(text = "推开世界的门 (Live) - 王源/周传雄"))
            add(NativeLyricLine(text = "Program：某人"))
            repeat(18) { index -> add(NativeLyricLine(text = "推开世界的门第${index}行")) }
        }
        assertTrue(ChineseLyricsPolicy.isFullyChinese(lines))
    }

    @Test
    fun `widespread english lines still block chinese detection`() {
        val lines = buildList {
            repeat(6) { index -> add(NativeLyricLine(text = "中文歌词第${index}行")) }
            repeat(4) { index -> add(NativeLyricLine(text = "曾被压榨的 Now walk on water $index")) }
        }
        assertFalse(ChineseLyricsPolicy.isFullyChinese(lines))
    }

    @Test
    fun `single non chinese line song stays non chinese`() {
        assertFalse(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(NativeLyricLine(text = "Baby 我早已明白了")),
            ),
        )
    }

    @Test
    fun `blank or missing lyrics are not fully chinese`() {
        assertFalse(ChineseLyricsPolicy.isFullyChinese(null as NativeLyricDocument?))
        assertFalse(ChineseLyricsPolicy.isFullyChinese(NativeLyricDocument(lyrics = null)))
        assertFalse(ChineseLyricsPolicy.isFullyChinese(listOf<NativeLyricLine>()))
        assertFalse(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(NativeLyricLine(text = "  "), NativeLyricLine(text = "")),
            ),
        )
    }

    @Test
    fun `lrc lines are judged by content`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChineseLrc(
                listOf(
                    OnlineTranslationLine(0, "感谢你曾来过", translation = "（在那个房间）"),
                    OnlineTranslationLine(1_000, "whoa-oh"),
                ),
            ),
        )
        assertFalse(
            ChineseLyricsPolicy.isFullyChineseLrc(
                listOf(OnlineTranslationLine(0, "Baby 感谢你曾来过")),
            ),
        )
    }

    @Test
    fun `extended interjection families are stripped`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "hah"),
                    NativeLyricLine(text = "ughh 想你"),
                    NativeLyricLine(text = "eww"),
                    NativeLyricLine(text = "oof 感谢你曾来过"),
                    NativeLyricLine(text = "ouch!"),
                    NativeLyricLine(text = "pfft"),
                    NativeLyricLine(text = "pffft"),
                    NativeLyricLine(text = "skrrt 想你"),
                    NativeLyricLine(text = "whoo 想见你"),
                    NativeLyricLine(text = "ayo 想你"),
                    NativeLyricLine(text = "phew~"),
                ),
            ),
        )
    }

    @Test
    fun `scat syllables and onomatopoeia are stripped`() {
        assertTrue(
            ChineseLyricsPolicy.isFullyChinese(
                listOf(
                    NativeLyricLine(text = "boom-boom 想你"),
                    NativeLyricLine(text = "doo-doo-doo"),
                    NativeLyricLine(text = "yeahyeah"),
                    NativeLyricLine(text = "bam! 感谢你曾来过"),
                    NativeLyricLine(text = "ding-dong"),
                ),
            ),
        )
    }

    @Test
    fun `lexical words stay non interjections`() {
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("who 想你"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("yup 想你"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("no 想你"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("mama 想你"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("papa 想你"))
        assertFalse(ChineseLyricsPolicy.isChineseOrNeutral("boombox 想你"))
    }

    @Test
    fun `strip english interjections keeps remaining text`() {
        assertEquals("我想你", ChineseLyricsPolicy.stripEnglishInterjections("oh我想你"))
        assertEquals("感谢你曾来过", ChineseLyricsPolicy.stripEnglishInterjections("whoa-oh 感谢你曾来过"))
        assertEquals("", ChineseLyricsPolicy.stripEnglishInterjections("whoa-oh-oh"))
        assertEquals("", ChineseLyricsPolicy.stripEnglishInterjections("oooh"))
    }
}
