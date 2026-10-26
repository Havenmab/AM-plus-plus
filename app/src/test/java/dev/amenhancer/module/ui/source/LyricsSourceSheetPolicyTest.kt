package dev.amenhancer.module.ui.source

import dev.amenhancer.module.lyrics.source.ApplyResult
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sheet's decisions that do not need an Android runtime: how the query is
 * composed, when a search may start, how stale callbacks are dropped and which
 * preview row the state belongs to. The view layer is not touched.
 */
class LyricsSourceSheetPolicyTest {

    @Test
    fun `query keeps the title first and the artist behind it`() {
        assertEquals(
            "晴天 周杰伦",
            LyricsSourceSearchPolicy.composeQuery(" 晴天 ", " 周杰伦 "),
        )
    }

    @Test
    fun `query drops whichever side is blank`() {
        assertEquals("晴天", LyricsSourceSearchPolicy.composeQuery("晴天", "   "))
        assertEquals("周杰伦", LyricsSourceSearchPolicy.composeQuery("", "周杰伦"))
        assertEquals("", LyricsSourceSearchPolicy.composeQuery(" ", ""))
    }

    @Test
    fun `metadata is searchable as soon as one side is filled`() {
        assertTrue(LyricsSourceSearchPolicy.isSearchable("晴天", ""))
        assertTrue(LyricsSourceSearchPolicy.isSearchable("", "周杰伦"))
        assertFalse(LyricsSourceSearchPolicy.isSearchable("   ", "\t"))
    }

    @Test
    fun `the same query is not searched twice`() {
        assertFalse(LyricsSourceSearchPolicy.shouldSchedule("晴天 周杰伦", "晴天 周杰伦"))
        assertTrue(LyricsSourceSearchPolicy.shouldSchedule("晴天 周杰伦", "晴天"))
        assertTrue(LyricsSourceSearchPolicy.shouldSchedule("晴天 周杰伦", null))
        assertFalse(LyricsSourceSearchPolicy.shouldSchedule("   ", null))
    }

    @Test
    fun `only the newest request token stays current`() {
        val token = LyricsSourceRequestToken()
        val first = token.next()
        val second = token.next()
        assertTrue(token.isCurrent(second))
        assertFalse(token.isCurrent(first))

        token.invalidate()
        assertFalse(token.isCurrent(second))
    }

    @Test
    fun `preview state reports the candidate it belongs to`() {
        assertNull(LyricsSourcePreviewState.None.optionKeyOrNull)
        assertEquals("online:qm:42", LyricsSourcePreviewState.Loading("online:qm:42").optionKeyOrNull)
        assertEquals(
            "online:qm:42",
            LyricsSourcePreviewState.Failed("online:qm:42", "失败").optionKeyOrNull,
        )
        assertEquals(
            "file:abc",
            LyricsSourcePreviewState.Loaded(
                optionKey = "file:abc",
                lines = listOf(LyricsSourcePreviewLine(original = "第一行")),
            ).optionKeyOrNull,
        )
    }

    @Test
    fun `busy state carries the lane and optionally the option`() {
        val hiding = LyricsSourceBusy(LyricLane.TRANSLATION, hiding = true)
        assertEquals(LyricLane.TRANSLATION, hiding.lane)
        assertNull(hiding.optionKey)
        assertTrue(hiding.hiding)
        assertFalse(hiding.restoring)

        val applying = LyricsSourceBusy(LyricLane.ORIGINAL, optionKey = "online:qm:42")
        assertEquals("online:qm:42", applying.optionKey)
        assertFalse(applying.hiding)
    }

    @Test
    fun `a host message wins over the sheet fallback`() {
        assertEquals(
            "Apple 自带译文暂不支持在面板内隐藏",
            ApplyResult.failed("not-supported", "Apple 自带译文暂不支持在面板内隐藏")
                .userMessageOr("无法切换歌词源"),
        )
        assertEquals(
            "无法切换歌词源",
            ApplyResult.failed("failed", "   ").userMessageOr("无法切换歌词源"),
        )
        assertEquals(
            "无法切换歌词源",
            ApplyResult(ok = false).userMessageOr("无法切换歌词源"),
        )
    }

    @Test
    fun `a source without observed precision claims nothing`() {
        assertNull(precisionClaim(precisionKnown = false, hasWords = true))
        assertNull(precisionClaim(precisionKnown = false, hasWords = false))
        assertEquals(LyricsSourcePrecisionClaim.WORDS, precisionClaim(true, true))
        assertEquals(LyricsSourcePrecisionClaim.LINES, precisionClaim(true, false))
    }

    @Test
    fun `a fetched preview remembers the precision it observed`() {
        val loaded = LyricsSourcePreviewState.Loaded(
            optionKey = "online:qm:42",
            lines = listOf(LyricsSourcePreviewLine(original = "第一行")),
            precisionKnown = true,
            hasWords = true,
        )
        assertEquals(LyricsSourcePrecisionClaim.WORDS, precisionClaim(loaded.precisionKnown, loaded.hasWords))
        assertNull(
            precisionClaim(
                LyricsSourcePreviewState.Loaded("online:ne:7", emptyList()).precisionKnown,
                LyricsSourcePreviewState.Loaded("online:ne:7", emptyList()).hasWords,
            ),
        )
    }

    @Test
    fun `the preview shows exactly three lines`() {
        assertEquals(3, LyricsSourceSearchPolicy.PREVIEW_LINES)
        assertTrue(LyricsSourceSearchPolicy.DEBOUNCE_MS in 200L..1_000L)
    }
}
