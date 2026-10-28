package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.online.LyricLaneClassifier.LaneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「译文被当成音译 / 音译被当成译文」的判定表。
 *
 * 每个用例都是一条真实的错位来源：QQ 音乐日语歌的 `trans` 是 `[kana:…]` + 罗马音、
 * 网易云的 `tlyric` 是中文、英文歌的「罗马音」只是原文的重复，以及旧 overlay 记录
 * 里已经把罗马音写进 translation 车道的那份数据。
 */
class LyricLaneClassifierTest {

    @Test
    fun `a qq kana tag plus romaji is a reading, never a translation`() {
        // Lemon 的实测形状：trans 以 [kana:…] 开头，正文是罗马音。
        val line = "[kana:れもん] yume naraba dorehodo yokatta deshou"

        assertEquals(LaneKind.ROMANIZATION, LyricLaneClassifier.classify(line))
        assertFalse(LyricLaneClassifier.isTranslationLike(line))
        assertTrue(LyricLaneClassifier.isRomanizationLike(line, "夢ならばどれほどよかったでしょう"))
    }

    @Test
    fun `a kana only line is a reading`() {
        assertEquals(LaneKind.KANA, LyricLaneClassifier.classify("ゆめならば"))
        assertTrue(LyricLaneClassifier.isRomanizationLike("ゆめならば"))
        assertFalse(LyricLaneClassifier.isTranslationLike("ゆめならば"))
        // ー ・ 「」 是 COMMON 字符，不能把整行推到「其他文字」。
        assertEquals(LaneKind.KANA, LyricLaneClassifier.classify("コーヒー"))
        assertEquals(LaneKind.KANA, LyricLaneClassifier.classify("「ずっと」"))
        assertTrue(LyricLaneClassifier.isRomanizationLike("コーヒー"))
    }

    @Test
    fun `a netease chinese tlyric line is a translation`() {
        val line = "如果这一切都是梦境该有多好"

        assertEquals(LaneKind.CHINESE, LyricLaneClassifier.classify(line))
        assertTrue(LyricLaneClassifier.isTranslationLike(line))
        assertFalse(LyricLaneClassifier.isRomanizationLike(line))
    }

    @Test
    fun `a japanese line belongs to neither lane`() {
        // 汉字＋假名是日语原文：写进译文车道就是这次的 bug，写进发音车道也一样错。
        assertEquals(LaneKind.MIXED, LyricLaneClassifier.classify("君の名は"))
        assertFalse(LyricLaneClassifier.isTranslationLike("君の名は"))
        assertFalse(LyricLaneClassifier.isRomanizationLike("君の名は"))
    }

    @Test
    fun `a chinese line with a foreign aside is still a translation`() {
        val line = "感谢你曾来过 (Thanks)"

        assertEquals(LaneKind.MIXED, LyricLaneClassifier.classify(line))
        assertTrue(LyricLaneClassifier.isTranslationLike(line))
        // 括注里的假名是「译文后面补的原名」，不是正文：整行丢掉会让这首诗少几行，
        // 只有这类行的歌更会 translationLines=0、整个候选被 no_meaningful_lane 排掉。
        assertTrue(LyricLaneClassifier.isTranslationLike("感谢你曾来过 (ありがとう)"))
        assertTrue(LyricLaneClassifier.isTranslationLike("别回头（振り向かないで）"))
        // 但假名在正文里（括注外）就仍然是日语原文，不能当译文发布。
        assertFalse(LyricLaneClassifier.isTranslationLike("ありがとう (谢谢)"))
        assertFalse(LyricLaneClassifier.isTranslationLike("夢ならば (Yume naraba)"))
        // 括注外一个汉字都没有时也不是译文。
        assertFalse(LyricLaneClassifier.isTranslationLike("(振り向かないで)"))
    }

    @Test
    fun `a chinese translation with japanese punctuation is still a translation`() {
        // 取证结论：标点从来不是字母，countScripts 只数 letter，所以「中文译文带日文
        // 标点被判非中文」这个猜测不成立——这条用例把结论钉住。
        listOf(
            "不要回头，继续走下去。",
            "「谢谢你」——她说。",
            "等等！？为什么…",
            "好想见你、好想见你",
        ).forEach { line ->
            assertTrue("line=$line", LyricLaneClassifier.isTranslationLike(line))
        }
    }

    @Test
    fun `blank punctuation tag only and numeric lines are empty`() {
        listOf(null, "", "   ", "//", "123", "[ti:Lemon]").forEach { line ->
            assertEquals("line=$line", LaneKind.EMPTY, LyricLaneClassifier.classify(line))
        }
        assertFalse(LyricLaneClassifier.isTranslationLike(""))
        assertFalse(LyricLaneClassifier.isRomanizationLike(""))
    }

    @Test
    fun `an english line is latin, not romaji`() {
        assertEquals(LaneKind.LATIN, LyricLaneClassifier.classify("Hello world"))
        assertEquals(LaneKind.LATIN, LyricLaneClassifier.classify("Second line"))
        assertFalse(LyricLaneClassifier.isRomanizationLike("Hello world"))
        assertFalse(LyricLaneClassifier.isTranslationLike("Hello world"))
    }

    @Test
    fun `an english original never gets a romanization`() {
        // 同脚本的「罗马音」只是原文，RomanizationPolicy 也是这么判的。
        assertFalse(LyricLaneClassifier.isRomanizationLike("Haro warudo", "Hello world"))
        assertTrue(LyricLaneClassifier.isRomanizationLike("Haro warudo", "君の名は"))
    }

    @Test
    fun `the same romaji in both lanes collapses to the romanization lane`() {
        // 截图里的现象：translation 与 romanization 都填了同一条罗马音。
        val lanes = LyricLaneClassifier.rebalance(
            translation = "yume naraba",
            romanization = "yume naraba",
            original = "夢ならば",
        )

        assertNull(lanes.translation)
        assertEquals("yume naraba", lanes.romanization)
    }

    @Test
    fun `a romaji translation moves into the empty romanization lane`() {
        val lanes = LyricLaneClassifier.rebalance(
            translation = "[kana:ゆめならば] yume naraba",
            romanization = null,
            original = "夢ならば",
        )

        assertNull("romaji must never be published as a translation", lanes.translation)
        // 搬过去的内容不带 QQ 的读音标签。
        assertEquals("yume naraba", lanes.romanization)
    }

    @Test
    fun `a chinese romanization moves back into the translation lane`() {
        // 反方向：音译字段里写的是中文译文。
        val lanes = LyricLaneClassifier.rebalance(
            translation = null,
            romanization = "如果这一切都是梦境",
            original = "夢ならば",
        )

        assertEquals("如果这一切都是梦境", lanes.translation)
        assertNull(lanes.romanization)
    }

    @Test
    fun `a reading identical to the original line is dropped`() {
        // 原文被抄进两条车道：它既不是译文也不是读音。
        val duplicated = LyricLaneClassifier.rebalance(
            translation = "ゆめならば",
            romanization = "ゆめならば",
            original = "ゆめならば",
        )
        assertTrue(duplicated.isEmpty)

        val fromTranslation = LyricLaneClassifier.rebalance(
            translation = "yume naraba",
            romanization = null,
            original = "yume naraba",
        )
        assertTrue(fromTranslation.isEmpty)
    }

    @Test
    fun `a correct lane pair is left exactly as it is`() {
        val lanes = LyricLaneClassifier.rebalance(
            translation = "第一",
            romanization = "di yi",
            original = "First",
        )

        assertEquals("第一", lanes.translation)
        assertEquals("di yi", lanes.romanization)
    }

    @Test
    fun `a stored lane set is re-homed without touching the row keys`() {
        // 旧 overlay 记录的落盘形状：两条按 (begin, end, text) 对齐的数组，而
        // translation 里存的是 QQ 读出来的罗马音/假名。
        val rows = listOf(
            LyricLaneClassifier.LaneRow(
                begin = 12_000L,
                end = 15_000L,
                text = "夢ならばどれほどよかったでしょう",
                translation = "yume naraba dorehodo yokatta deshou",
            ),
            LyricLaneClassifier.LaneRow(
                begin = 15_000L,
                end = 18_000L,
                text = "未だにあなたのことを夢にみる",
                translation = "まだにあなたのことをゆめにみる",
            ),
            LyricLaneClassifier.LaneRow(
                begin = 18_000L,
                end = 21_000L,
                text = "If this were all a dream",
                translation = "如果这一切都是梦境",
            ),
        )

        val corrected = LyricLaneClassifier.rebalanceRows(rows)

        assertEquals(rows.map { it.begin }, corrected.map { it.begin })
        assertEquals(rows.map { it.end }, corrected.map { it.end })
        assertEquals(rows.map { it.text }, corrected.map { it.text })
        assertNull(corrected[0].translation)
        assertEquals("yume naraba dorehodo yokatta deshou", corrected[0].romanization)
        assertNull(corrected[1].translation)
        assertEquals("まだにあなたのことをゆめにみる", corrected[1].romanization)
        assertEquals("如果这一切都是梦境", corrected[2].translation)
        assertNull(corrected[2].romanization)
    }
}
