package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses netease colon centisecond timestamps`() {
        val parsed = LrcParser.parseLrc(
            "[00:27:76]瞳映る　静かな世界　なにを見てたんだろう\n" +
                "[00:39:15]優しい思い出　ふさいだ鍵穴"
        )

        assertEquals(2, parsed.size)
        assertEquals(27_760L, parsed[0].start)
        assertEquals("瞳映る　静かな世界　なにを見てたんだろう", parsed[0].words.single().text)
        assertEquals(39_150L, parsed[1].start)
    }

    @Test
    fun `keeps parsing dot millisecond timestamps`() {
        val parsed = LrcParser.parseLrc(
            "[00:27.760]瞳映る\n" +
                "[00:39.15]優しい"
        )

        assertEquals(2, parsed.size)
        assertEquals(27_760L, parsed[0].start)
        assertEquals(39_150L, parsed[1].start)
    }

    @Test
    fun `line end comes from the next timestamp and the last line gets a three second tail`() {
        val parsed = LrcParser.parseLrc(
            "[00:01.000]first\n" +
                "[00:03.500]second"
        )

        assertEquals(1_000L, parsed[0].start)
        assertEquals(3_500L, parsed[0].end)
        assertEquals(3_500L, parsed[1].start)
        assertEquals(6_500L, parsed[1].end)
    }

    @Test
    fun `metadata and malformed lines are ignored`() {
        val parsed = LrcParser.parseLrc(
            "[ti:Some Song]\n" +
                "[ar:Some Artist]\n" +
                "[by:someone]\n" +
                "not a timed line\n" +
                "[00:xx.yy]broken timestamp\n" +
                "[00:05.000]real line"
        )

        assertEquals(1, parsed.size)
        assertEquals(5_000L, parsed[0].start)
        assertEquals("real line", parsed[0].words.single().text)
    }

    @Test
    fun `malformed input alone parses to nothing`() {
        assertTrue(LrcParser.parseLrc("").isEmpty())
        assertTrue(LrcParser.parseLrc("[ti:only metadata]").isEmpty())
        assertTrue(LrcParser.parseLrc("plain text").isEmpty())
    }

    @Test
    fun `several timestamps on one line duplicate the shared text`() {
        val parsed = LrcParser.parseLrc("[00:01.000][00:04.000]repeat")

        assertEquals(2, parsed.size)
        assertEquals(1_000L, parsed[0].start)
        assertEquals(4_000L, parsed[1].start)
        assertEquals("repeat", parsed[0].words.single().text)
        assertEquals("repeat", parsed[1].words.single().text)
    }

    @Test
    fun `matches translation to the nearest original timestamp instead of the next window entry`() {
        val original = listOf(
            line(47_780L, "Eating halal"),
            line(49_490L, "Told that I am sorry"),
            line(51_250L, "Bout my coins like Mario")
        )
        val translations = listOf(
            line(47_380L, "开着兰博基尼"),
            line(49_260L, "告诉那个人我很抱歉"),
            line(50_930L, "我的硬币像 Mario 一样")
        )

        val merged = LrcParser.lyricsMerge(original, translations)

        assertEquals(
            listOf("开着兰博基尼", "告诉那个人我很抱歉", "我的硬币像 Mario 一样"),
            merged?.map { lyricLine -> lyricLine.words.joinToString("") { it.text } }
        )
    }

    @Test
    fun `no translation track merges to null`() {
        assertEquals(null, LrcParser.lyricsMerge(listOf(line(0L, "a")), null))
        assertEquals(null, LrcParser.lyricsMerge(listOf(line(0L, "a")), emptyList<LyricsLine>()))
    }

    private fun line(start: Long, text: String) = LyricsLine(
        start = start,
        end = start + 1_000L,
        words = listOf(LyricsWord(start, start + 1_000L, text))
    )
}
