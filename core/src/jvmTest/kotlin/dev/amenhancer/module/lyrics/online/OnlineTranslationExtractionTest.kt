package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationExtractionTest {

    private fun word(begin: Long, end: Long, text: String) = LyricsWord(begin, end, text)

    @Test
    fun `extraction folds the provider lanes into one aligned line per original`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(1_000L, 2_000L, listOf(word(1_000L, 2_000L, "First"))),
                LyricsLine(2_000L, 3_000L, listOf(word(2_000L, 3_000L, "Second"))),
            ),
            translated = listOf(
                LyricsLine(1_000L, 2_000L, listOf(word(1_000L, 2_000L, "第一"))),
                LyricsLine(2_000L, 3_000L, listOf(word(2_000L, 3_000L, "第二"))),
            ),
            romanization = listOf(
                LyricsLine(1_000L, 2_000L, listOf(word(1_000L, 2_000L, "di yi"))),
                LyricsLine(2_000L, 3_000L, listOf(word(2_000L, 3_000L, "di er"))),
            ),
        )

        val extracted = OnlineTranslationExtraction.extract(result)

        assertEquals(2, extracted.size)
        assertEquals(
            OnlineTranslationLine(1_000L, "First", "第一", "di yi"),
            extracted[0],
        )
        assertEquals(
            OnlineTranslationLine(2_000L, "Second", "第二", "di er"),
            extracted[1],
        )
    }

    @Test
    fun `extraction joins word text and trims it`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(0L, 1_000L, listOf(word(0L, 400L, "Hel"), word(400L, 1_000L, "lo "))),
            ),
            translated = null,
            romanization = null,
        )

        val extracted = OnlineTranslationExtraction.extract(result).single()

        assertEquals("Hello", extracted.content)
        assertNull(extracted.translation)
        assertNull(extracted.romanization)
    }

    @Test
    fun `extraction sanitizes slash only translation placeholders`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(LyricsLine(0L, 1_000L, listOf(word(0L, 1_000L, "Listen")))),
            translated = listOf(
                LyricsLine(0L, 1_000L, listOf(word(0L, 1_000L, "// //"))),
            ),
            romanization = listOf(
                LyricsLine(0L, 1_000L, listOf(word(0L, 1_000L, "  "))),
            ),
        )

        val extracted = OnlineTranslationExtraction.extract(result).single()

        assertNull(extracted.translation)
        assertNull(extracted.romanization)
    }

    @Test
    fun `a shorter lane leaves the tail lines without content`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(0L, 1_000L, listOf(word(0L, 1_000L, "First"))),
                LyricsLine(1_000L, 2_000L, listOf(word(1_000L, 2_000L, "Second"))),
            ),
            translated = listOf(
                LyricsLine(0L, 1_000L, listOf(word(0L, 1_000L, "第一"))),
            ),
            romanization = null,
        )

        val extracted = OnlineTranslationExtraction.extract(result)

        assertEquals("第一", extracted[0].translation)
        assertNull(extracted[1].translation)
    }
}
