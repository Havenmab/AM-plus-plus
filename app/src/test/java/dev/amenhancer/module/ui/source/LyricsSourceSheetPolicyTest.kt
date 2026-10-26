package dev.amenhancer.module.ui.source

import dev.amenhancer.module.lyrics.source.ApplyResult
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricsSourcePreviewLine
import java.util.Locale
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
    fun `timing badge names static lyrics and hides unknown precision`() {
        val zh = Locale.SIMPLIFIED_CHINESE
        assertEquals("无时间轴", precisionLabel(option(hasTiming = false, precisionKnown = true, hasWords = true), zh))
        assertEquals("无时间轴", precisionLabel(option(hasTiming = false, precisionKnown = false, hasWords = false), zh))
        assertNull(precisionLabel(option(hasTiming = true, precisionKnown = false, hasWords = true), zh))
        assertEquals("逐词", precisionLabel(option(hasTiming = true, precisionKnown = true, hasWords = true), zh))
        assertEquals("逐行", precisionLabel(option(hasTiming = true, precisionKnown = true, hasWords = false), zh))
    }

    @Test
    fun `a fetched preview keeps the timing it observed`() {
        val zh = Locale.SIMPLIFIED_CHINESE
        val wordTimed = LyricsSourcePreviewState.Loaded(
            optionKey = "online:qm:42",
            lines = listOf(LyricsSourcePreviewLine(original = "第一行")),
            precisionKnown = true,
            hasWords = true,
            hasTiming = true,
        )
        assertEquals("逐词", previewTimingLabel(wordTimed.hasTiming, wordTimed.precisionKnown, wordTimed.hasWords, zh))

        val static = LyricsSourcePreviewState.Loaded(
            optionKey = "online:ne:7",
            lines = listOf(LyricsSourcePreviewLine(original = "第一行")),
            precisionKnown = true,
            hasWords = false,
            hasTiming = false,
        )
        assertEquals("无时间轴", previewTimingLabel(static.hasTiming, static.precisionKnown, static.hasWords, zh))

        val unknown = LyricsSourcePreviewState.Loaded(
            optionKey = "online:kuwo:9",
            lines = listOf(LyricsSourcePreviewLine(original = "第一行")),
        )
        assertNull(previewTimingLabel(unknown.hasTiming, unknown.precisionKnown, unknown.hasWords, zh))
    }

    private fun option(hasTiming: Boolean, precisionKnown: Boolean, hasWords: Boolean): LyricSourceOption =
        LyricSourceOption(
            key = "test",
            kind = LyricSourceKind.APPLE,
            providerId = "apple",
            label = "Apple Music",
            hasWords = hasWords,
            precisionKnown = precisionKnown,
            hasTiming = hasTiming,
        )

    @Test
    fun `the preview shows exactly three lines`() {
        assertEquals(3, LyricsSourceSearchPolicy.PREVIEW_LINES)
        assertTrue(LyricsSourceSearchPolicy.DEBOUNCE_MS in 200L..1_000L)
    }
}
