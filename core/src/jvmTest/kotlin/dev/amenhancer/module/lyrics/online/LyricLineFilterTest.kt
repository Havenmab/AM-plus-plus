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

    // 1. 署名行：冒号写法 --------------------------------------------------------

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

    // 1. 署名行：无冒号写法 ------------------------------------------------------

    @Test
    fun `credit lines written without a colon are removed`() {
        val body = listOf(
            line(0, "作词 周杰伦"),
            line(1_000, "作曲 周杰伦"),
            line(2_000, "编曲 钟兴民"),
            line(3_000, "制作人 周杰伦"),
            line(4_000, "吉他 蔡科俊"),
            line(5_000, "录音室 JVR Studio"),
            line(6_000, "混音师 杨大纬"),
            line(7_000, "统筹　王xx"),
            line(20_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    // 1. 署名行：扩充后的词表 ----------------------------------------------------

    @Test
    fun `the extended credit vocabulary is removed`() {
        val credits = listOf(
            "词曲：李荣浩", "作词人：李荣浩", "作曲人：李荣浩", "编曲人：陈伟", "监制：李荣浩",
            "出品：华纳唱片", "发行：华纳唱片", "OP：华纳音乐", "SP：华纳音乐", "演唱：李荣浩",
            "原唱：李荣浩", "和声：李荣浩", "和声编写：李荣浩", "录音：张xx", "录音师：张xx",
            "录音室：野火春风", "录音助理：王xx", "人声录音：李xx", "人声编辑：李xx", "混音：陈xx",
            "混音师：陈xx", "母带：Chris", "母带师：Chris", "吉他：张xx", "贝斯：张xx",
            "鼓：李xx", "键盘：张xx", "弦乐：国际首席爱乐乐团", "统筹：王xx", "企划：李xx",
            "宣发：王xx", "翻译：李xx", "校对：张xx", "LRC制作：李xx", "歌词制作：李xx",
            "字幕：李xx", "后期：李xx",
        )
        val body = credits.mapIndexed { index, credit -> line(index * 500L, credit) } +
            line(40_000, "年少有为不自卑")
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("年少有为不自卑"), texts(filtered.original))
    }

    // 1. 署名行：英文写法 --------------------------------------------------------

    @Test
    fun `english credits are removed`() {
        val body = listOf(
            line(0, "Lyrics by 周杰伦"),
            line(500, "Composed by 周杰伦"),
            line(1_000, "Arranged by 钟兴民"),
            line(1_500, "Produced by 周杰伦"),
            line(2_000, "Mixed by 杨大纬"),
            line(2_500, "Mastered by Chris Gehringer"),
            line(3_000, "Written by Diane Warren"),
            line(3_500, "Vocals by 周杰伦"),
            line(4_000, "Performed by 周杰伦"),
            line(4_500, "Recorded at JVR Studio"),
            line(30_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    // 1. 署名行：窗口放宽到前 60 秒 / 前 20 行 ------------------------------------

    @Test
    fun `a credit block clears shapes up to the sixty second window`() {
        // Two shapes inside the first 20 lines make a block, so the second one is dropped even
        // though it lands far past the lone-credit window.
        val body = listOf(
            line(0, "作词：周杰伦"),
            line(45_000, "作曲：周杰伦"),
            line(48_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    // 2. 署名块判定 --------------------------------------------------------------

    @Test
    fun `a qq credit block disappears together with its title header`() {
        val body = listOf(
            line(0, "晴天 - 周杰伦"),
            line(1_000, "词：周杰伦"),
            line(2_000, "曲：周杰伦"),
            line(3_000, "编曲：钟兴民"),
            line(4_000, "制作人：周杰伦"),
            line(5_000, "吉他：蔡科俊"),
            line(6_000, "和声：周杰伦"),
            line(7_000, "录音师：杨大纬"),
            line(8_000, "混音师：杨大纬"),
            line(9_000, "母带：Chris Gehringer"),
            line(10_000, "出品：杰威尔音乐"),
            line(11_000, "发行：杰威尔音乐"),
            line(28_950, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    @Test
    fun `the opening block is swept as a unit`() {
        // The block rule only removes credit-shaped lines; the verse that sits between two
        // credits is a lyric and has to survive the block.
        val body = listOf(
            line(0, "作词：周杰伦"),
            line(2_000, "故事的小黄花"),
            line(4_000, "作曲：周杰伦"),
            line(20_000, "从出生那年就飘着"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("从出生那年就飘着"), texts(filtered.original))
    }

    // 1./3. 单条署名：8 秒内删，8 秒后保留（保护真实歌词） ------------------------

    @Test
    fun `a lone credit shape inside the first eight seconds is still a credit`() {
        val body = listOf(
            line(3_000, "作词：周杰伦"),
            line(20_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    @Test
    fun `a lone credit shape far into the song stays a lyric`() {
        val body = listOf(
            line(30_000, "作曲：上帝"),
            line(33_000, "他写下这段旋律"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("他写下这段旋律"), texts(filtered.original))
    }

    @Test
    fun `a lone credit shape is removed wherever it appears`() {
        val body = listOf(
            line(8_500, "作词：周杰伦"),
            line(20_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    @Test
    fun `credit keys inside a real lyric are not credits`() {
        val body = listOf(
            line(5_000, "曲终人散"),
            line(5_500, "词不达意"),
            line(6_000, "鼓声响起"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("曲终人散", "词不达意", "鼓声响起"), texts(filtered.original))
    }

    // 3. 平台 / 版权行：任何位置 ------------------------------------------------

    @Test
    fun `every platform and copyright line is removed at any position`() {
        val body = listOf(
            line(90_000, "© 2020 杰威尔音乐有限公司"),
            line(91_000, "℗ 2020 JVR Music"),
            line(92_000, "All rights reserved."),
            line(93_000, "Provided to YouTube by JVR Music"),
            line(94_000, "Auto-generated by YouTube."),
            line(95_000, "Released on: 2003-07-31"),
            line(96_000, "under exclusive license to Sony Music"),
            line(97_000, "网易云音乐"),
            line(98_000, "酷狗音乐"),
            line(99_000, "酷我音乐"),
            line(100_000, "歌词来自网络"),
            line(101_000, "未经许可不得翻唱"),
            line(102_000, "纯音乐，请欣赏"),
            line(103_000, "此歌曲暂无歌词"),
            line(104_000, "此歌曲由Vemus未音APP制作"),
            line(105_000, "关注我们"),
            line(106_000, "订阅频道"),
            line(107_000, "扫码下载客户端"),
            line(110_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("故事的小黄花"), texts(filtered.original))
    }

    @Test
    fun `the tme notice goes while treatment stays a lyric`() {
        val body = listOf(
            line(0, "TME享有本翻译作品的著作权"),
            line(20_000, "Treatment of the night"),
            line(25_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("Treatment of the night", "故事的小黄花"), texts(filtered.original))
    }

    @Test
    fun `a lyric that mentions the word survives the call to action rule`() {
        val body = listOf(
            line(20_000, "我一直关注着你"),
            line(21_000, "关注着你的背影"),
            line(22_000, "关注公众号：xxx"),
            line(23_000, "关注"),
            line(25_000, "故事的小黄花"),
        )
        val filtered = LyricLineFilter.filter(result(body))
        assertEquals(listOf("我一直关注着你", "关注着你的背影", "故事的小黄花"), texts(filtered.original))
    }

    // 4. 歌名头行 ----------------------------------------------------------------

    @Test
    fun `the title header is dropped even without the song identity`() {
        val body = listOf(
            line(0, "晴天 - 周杰伦 (Jay Chou)"),
            line(2_000, "词：周杰伦"),
            line(4_000, "曲：周杰伦"),
            line(6_000, "编曲：周杰伦"),
            line(29_000, "故事的小黄花"),
        )
        assertEquals(listOf("故事的小黄花"), texts(LyricLineFilter.filter(result(body)).original))
    }

    @Test
    fun `the title header is dropped with an en dash`() {
        val body = listOf(
            line(0, "晴天 – 周杰伦"),
            line(20_000, "故事的小黄花"),
        )
        assertEquals(listOf("故事的小黄花"), texts(LyricLineFilter.filter(result(body)).original))
    }

    @Test
    fun `the title header is dropped when it matches the song identity`() {
        val body = listOf(
            line(0, "晴天-周杰伦"),
            line(20_000, "故事的小黄花"),
        )
        assertEquals(
            listOf("故事的小黄花"),
            texts(LyricLineFilter.filter(result(body), songTitle = "晴天", songArtist = "周杰伦").original),
        )
        // Without the identity the tight separator proves nothing, so the line is left alone.
        assertTrue(texts(LyricLineFilter.filter(result(body)).original).contains("晴天-周杰伦"))
    }

    @Test
    fun `a header shaped lyric after the opening survives`() {
        val body = listOf(
            line(30_000, "我们 - 走散了"),
            line(40_000, "各自安好"),
        )
        assertEquals(
            listOf("我们 - 走散了", "各自安好"),
            texts(LyricLineFilter.filter(result(body), songTitle = "晴天", songArtist = "周杰伦").original),
        )
    }

    // 5. 空壳 / 占位 -------------------------------------------------------------

    @Test
    fun `placeholder shells are removed`() {
        val body = listOf(
            line(0, "//"),
            line(1_000, "///"),
            line(2_000, "…"),
            line(3_000, "♪"),
            line(4_000, "00:12.30"),
            line(5_000, "[00:12.34]"),
            line(6_000, "---"),
            line(7_000, "•••"),
            line(8_000, "1 2 3 4"),
            line(20_000, "故事的小黄花"),
        )
        assertEquals(listOf("故事的小黄花"), texts(LyricLineFilter.filter(result(body)).original))
    }

    // 6. 重复行 ------------------------------------------------------------------

    @Test
    fun `provider duplicates collapse`() {
        val body = listOf(
            line(10_000, "刮风这天我试过握着你手"),
            line(11_200, "刮风这天我试过握着你手"),
            line(12_000, "刮风这天，我试过握着你手"),
            line(16_000, "但偏偏雨渐渐大到我看你不见"),
        )
        assertEquals(
            listOf("刮风这天我试过握着你手", "但偏偏雨渐渐大到我看你不见"),
            texts(LyricLineFilter.filter(result(body)).original),
        )
    }

    @Test
    fun `a chorus repeat outside the duplicate window survives`() {
        val body = listOf(
            line(10_000, "刮风这天我试过握着你手"),
            line(15_000, "刮风这天我试过握着你手"),
        )
        assertEquals(
            listOf("刮风这天我试过握着你手", "刮风这天我试过握着你手"),
            texts(LyricLineFilter.filter(result(body)).original),
        )
    }

    // 7. 翻译车道 ----------------------------------------------------------------

    @Test
    fun `the translation lane loses notices, credits and duplicates`() {
        val original = listOf(
            line(0, "When I find myself"),
            line(4_000, "Mother Mary comes to me"),
            line(8_000, "Speaking words of wisdom"),
        )
        val translated = listOf(
            line(0, "QQ音乐享有本翻译作品的著作权"),
            line(1_000, "翻译：张三"),
            line(2_000, "//"),
            line(4_000, "圣母玛利亚来到我身边"),
            line(5_000, "圣母玛利亚，来到我身边"),
            line(8_000, "说着智慧的话语"),
        )
        val filtered = LyricLineFilter.filter(result(original, translated))
        assertEquals(
            listOf("When I find myself", "Mother Mary comes to me", "Speaking words of wisdom"),
            texts(filtered.original),
        )
        assertEquals(listOf("圣母玛利亚来到我身边", "说着智慧的话语"), texts(filtered.translated!!))
    }

    // 不误删 ----------------------------------------------------------------------

    @Test
    fun `a plain lyric body passes through untouched`() {
        val body = listOf(
            line(0, "故事的小黄花"),
            line(3_500, "从出生那年就飘着"),
            line(7_000, "童年的荡秋千"),
        )
        assertEquals(
            listOf("故事的小黄花", "从出生那年就飘着", "童年的荡秋千"),
            texts(LyricLineFilter.filter(result(body)).original),
        )
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
        assertEquals(emptyList<String>(), texts(filtered.original))
    }
}
