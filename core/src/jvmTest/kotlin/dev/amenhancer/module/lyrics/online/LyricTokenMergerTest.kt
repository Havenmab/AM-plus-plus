package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules in [LyricTokenMerger], exercised on the shapes that actually show up in
 * provider karaoke data: loan-word katakana, kanji compounds, hiragana runs with particles,
 * and the guards that refuse a merge.
 */
class LyricTokenMergerTest {

    private fun token(text: String, romanization: String?, begin: Long, end: Long) =
        PronunciationToken(text = text, romanization = romanization, begin = begin, end = end)

    @Test
    fun `a katakana loan word merges while a lone katakana stays alone`() {
        val tokens = listOf(
            token("マ", "ma", 0, 200),
            token("ジ", "ji", 200, 400),
            token("カ", "ka", 400, 600),
            token("ル", "ru", 600, 800),
            token("ト", "to", 800, 1_000),
            token("リ", "rik", 1_000, 1_200),
            token("ッ", null, 1_200, 1_260),
            token("ク", "ku", 1_260, 1_450),
            token("で", "de", 1_450, 1_650),
        )

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(listOf("マジカル", "ト", "リック", "で"), groups.map { it.text })
        assertEquals(listOf("majikaru", "to", "rikku", "de"), groups.map { it.romanization })
        assertEquals(0L, groups[0].begin)
        assertEquals(800L, groups[0].end)
        assertEquals(4, groups[0].parts.size)
        assertTrue(groups[0].isMerged)
        assertFalse(groups[1].isMerged)
    }

    @Test
    fun `kanji compounds merge and do not absorb okurigana`() {
        val tokens = listOf(
            token("東", "tou", 0, 200),
            token("京", "kyou", 200, 400),
            token("で", "de", 400, 600),
            token("食", "ta", 600, 800),
            token("べ", "be", 800, 1_000),
        )

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(listOf("東京", "で", "食", "べ"), groups.map { it.text })
        assertEquals(listOf("toukyou", "de", "ta", "be"), groups.map { it.romanization })
    }

    @Test
    fun `a hiragana run merges but a single particle splits it`() {
        val tokens = listOf(
            token("こ", "ko", 0, 200),
            token("れ", "re", 200, 400),
            token("は", "ha", 400, 600),
            token("あ", "a", 600, 800),
            token("り", "ri", 800, 1_000),
            token("が", "ga", 1_000, 1_200),
            token("と", "to", 1_200, 1_400),
            token("う", "u", 1_400, 1_600),
        )

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(listOf("これ", "は", "あり", "が", "とう"), groups.map { it.text })
        assertEquals(listOf("kore", "ha", "ari", "ga", "tou"), groups.map { it.romanization })
    }

    @Test
    fun `a long-vowel mark and small kana attach to the group before them`() {
        val tokens = listOf(
            token("コ", "ko", 0, 200),
            token("ン", "n", 200, 400),
            token("ピ", "pi", 400, 600),
            token("ュ", "yu", 600, 800),
            token("ー", "a", 800, 1_000),
            token("タ", "ta", 1_000, 1_200),
        )

        val groups = LyricTokenMerger.merge(tokens)

        // The long-vowel mark attaches, so the whole loan word stays one group.
        assertEquals(listOf("コンピューター"), groups.map { it.text })
        assertEquals("konpiyuata", groups[0].romanization)
    }

    @Test
    fun `a group longer than the cap is split back into tokens`() {
        val tokens = (0 until 7).map { index ->
            token("カ", "ka", index * 200L, index * 200L + 200L)
        }

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(7, groups.size)
        assertTrue(groups.none { it.isMerged })
    }

    @Test
    fun `a silence inside the run refuses the merge`() {
        val tokens = listOf(
            token("マ", "ma", 0, 200),
            token("ジ", "ji", 2_000, 2_200),
        )

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(listOf("マ", "ジ"), groups.map { it.text })
    }

    @Test
    fun `a token without a romanization refuses the merge`() {
        val tokens = listOf(
            token("マ", "ma", 0, 200),
            token("ジ", null, 200, 400),
        )

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(listOf("マ", "ジ"), groups.map { it.text })
    }

    @Test
    fun `punctuation stands alone and breaks the run`() {
        val tokens = listOf(
            token("マ", "ma", 0, 200),
            token("、", null, 200, 260),
            token("ジ", "ji", 260, 460),
        )

        val groups = LyricTokenMerger.merge(tokens)

        assertEquals(listOf("マ", "、", "ジ"), groups.map { it.text })
    }

    @Test
    fun `every input token appears exactly once`() {
        val tokens = listOf(
            token("マ", "ma", 0, 200),
            token("ジ", "ji", 200, 400),
            token("カ", "ka", 400, 600),
            token("で", "de", 600, 800),
            token("(", null, 800, 860),
            token("東", "tou", 860, 1_060),
        )

        val groups = LyricTokenMerger.merge(tokens)
        val flattened = groups.flatMap { it.parts }

        assertEquals(tokens, flattened)
    }
}
