package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ported from HLE's `common.lyric.OnlineTranslationMatchStatsTest` at `adead6d`.
 */
class OnlineTranslationMatchStatsTest {
    @Test
    fun `codec preserves match counts and rounds percentage`() {
        val encoded = OnlineTranslationMatchStatsCodec.encode(
            mapOf(
                "NE" to OnlineTranslationMatchStat(matchedLines = 2, totalLines = 3),
                "QM" to OnlineTranslationMatchStat(matchedLines = 3, totalLines = 3),
            ),
        )

        val decoded = OnlineTranslationMatchStatsCodec.decode(encoded)

        assertEquals(2, decoded["NE"]?.matchedLines)
        assertEquals(67, decoded["NE"]?.percentage)
        assertEquals(100, decoded["QM"]?.percentage)
    }
}
