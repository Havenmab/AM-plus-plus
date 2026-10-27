package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real provider bodies collected while investigating the credit/notice lines. */
class LyricLineFilterTest {

    private fun line(start: Long, text: String) = LyricsLine(
        start = start,
        end = start + 2_000L,
        words = listOf(LyricsWord(start = start, end = start + 2_000L, text = text)),
    )

    private fun result(
        original: List<LyricsLine>,
        translated: List<LyricsLine>? = null,
        romanization: List<LyricsLine>? = null,
    ) = LyricsResult(tags = emptyMap(), original = original, translated = translated, romanization = romanization)

    private fun texts(lines: List<LyricsLine>) = lines.map { line ->
        line.words.joinToString("") { it.text }
    }

    @Test
    fun `the netease credit block is removed and the first lyric survives`() {
        val body = listOf(
            line(0, " 作词 : 周杰伦"),
            line(1_000, " 作曲 : 周杰伦"),
            line(2_000, " 编曲 : 周杰伦"),
            line(3_000, " 制作人 : 周杰伦"),
            line(4_000, " 和声编写 : 周杰伦"),
            line(5_000, " 吉他 : 蔡科俊Again"),
            line(6_000, " 贝斯 : 陈任佑"),
            line(28_950, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    @Test
    fun `qq music copyright notices never become lyric lines`() {
        val body = listOf(
            line(0, "QQ音乐享有本翻译作品的著作权"),
            line(1_000, "TME享有本翻译作品的著作权"),
            line(2_000, "以下歌词翻译由文曲大模型提供"),
            line(3_000, "When I find myself in times of trouble"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("When I find myself in times of trouble"), texts(filtered.original))
    }

    @Test
    fun `a translation lane loses its notices and slash placeholders`() {
        val original = listOf(line(0, "When I find myself"), line(4_000, "Mother Mary comes to me"))
        val translated = listOf(
            line(0, "QQ音乐享有本翻译作品的著作权"),
            line(1_000, "//"),
            line(4_000, "圣母玛利亚来到我身边"),
        )
        val filtered = LyricLineFilter.filter(result(original, translated))
        assertEquals(listOf("圣母玛利亚来到我身边"), texts(filtered.translated!!))
    }

    @Test
    fun `a single credit-shaped line is treated as a lyric`() {
        val body = listOf(
            line(30_000, "作曲：上帝"),
            line(33_000, "他写下这段旋律"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("作曲：上帝", "他写下这段旋律"), texts(filtered.original))
    }

    @Test
    fun `the title header line is dropped only when the song is known`() {
        val body = listOf(
            line(0, "晴天 - 周杰伦 (Jay Chou)"),
            line(2_000, "词：周杰伦"),
            line(4_000, "曲：周杰伦"),
            line(6_000, "编曲：周杰伦"),
            line(29_000, "故事的小黄花"),
        )
        assertEquals(
            listOf("故事的小黄花"),
            texts(LyricLineFilter.filter(result(body), songTitle = "晴天", songArtist = "周杰伦").original),
        )
        // Without the song identity the header cannot be proven, so it is kept.
        assertTrue(texts(LyricLineFilter.filter(result(body)).original).contains("晴天 - 周杰伦 (Jay Chou)"))
    }

    @Test
    fun `platform noise and placeholder bodies are removed`() {
        val body = listOf(
            line(0, "「此歌曲由Vemus未音APP制作 音乐创作如此简单！」"),
            line(2_000, "纯音乐，请欣赏"),
            line(4_000, "♪"),
            line(6_000, "从前从前有个人爱你很久"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("从前从前有个人爱你很久"), texts(filtered.original))
    }

    @Test
    fun `provider duplicates collapse`() {
        val body = listOf(
            line(10_000, "刮风这天我试过握着你手"),
            line(10_800, "刮风这天我试过握着你手"),
            line(16_000, "但偏偏雨渐渐大到我看你不见"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("刮风这天我试过握着你手", "但偏偏雨渐渐大到我看你不见"), texts(filtered.original))
    }

    @Test
    fun `a colon inside a real lyric survives`() {
        val body = listOf(
            line(12_000, "他说：别回头"),
            line(15_000, "作词 : 周杰伦"),
            line(16_000, "作曲 : 周杰伦"),
            line(17_000, "编曲 : 周杰伦"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("他说：别回头"), texts(filtered.original))
    }
}