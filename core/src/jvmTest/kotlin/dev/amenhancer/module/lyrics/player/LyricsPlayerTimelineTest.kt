package dev.amenhancer.module.lyrics.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlayerTimelineTest {
    private val lines = listOf(
        PlayerLine(startMs = 0, endMs = 1_000, text = "one", words = listOf(PlayerWord(0, 1_000, "one"))),
        PlayerLine(startMs = 1_000, endMs = 2_000, text = "two", words = listOf(PlayerWord(1_000, 2_000, "two"))),
        PlayerLine(startMs = 2_000, endMs = 3_000, text = "three", words = listOf(PlayerWord(2_000, 3_000, "three"))),
    )

    @Test
    fun `current line is the last one that already started`() {
        assertEquals(-1, LyricsPlayerTimeline.lineIndexAt(emptyList(), 0))
        assertEquals(0, LyricsPlayerTimeline.lineIndexAt(lines, -100))
        assertEquals(0, LyricsPlayerTimeline.lineIndexAt(lines, 0))
        assertEquals(0, LyricsPlayerTimeline.lineIndexAt(lines, 999))
        assertEquals(1, LyricsPlayerTimeline.lineIndexAt(lines, 1_000))
        assertEquals(1, LyricsPlayerTimeline.lineIndexAt(lines, 1_500))
        assertEquals(2, LyricsPlayerTimeline.lineIndexAt(lines, 9_999))
        assertEquals(2, LyricsPlayerTimeline.lineIndexAt(lines, 2_000))
    }

    @Test
    fun `line progress follows the line window`() {
        assertEquals(0f, LyricsPlayerTimeline.lineProgress(lines[1], 0), 1e-5f)
        assertEquals(0.5f, LyricsPlayerTimeline.lineProgress(lines[1], 1_500), 1e-5f)
        assertEquals(1f, LyricsPlayerTimeline.lineProgress(lines[1], 5_000), 1e-5f)
        val zeroLength = PlayerLine(startMs = 10, endMs = 10, text = "x", words = emptyList())
        assertEquals(1f, LyricsPlayerTimeline.lineProgress(zeroLength, 10), 1e-5f)
        assertEquals(0f, LyricsPlayerTimeline.lineProgress(zeroLength, 9), 1e-5f)
    }

    @Test
    fun `word highlight reports the current word and how many are done`() {
        val line = PlayerLine(
            startMs = 0,
            endMs = 900,
            text = "abc",
            words = listOf(
                PlayerWord(0, 200, "a"),
                PlayerWord(200, 500, "b"),
                PlayerWord(500, 900, "c"),
            ),
        )
        fun highlight(at: Long) = LyricsPlayerTimeline.wordHighlight(line, at)

        assertEquals(0, highlight(-10).wordIndex)
        assertEquals(0f, highlight(-10).wordProgress, 1e-5f)
        assertEquals(0, highlight(-10).completedWords)

        val first = highlight(100)
        assertEquals(0, first.wordIndex)
        assertEquals(0.5f, first.wordProgress, 1e-5f)
        assertEquals(0, first.completedWords)

        val second = highlight(250)
        assertEquals(1, second.wordIndex)
        assertEquals(1f / 6f, second.wordProgress, 1e-5f)
        assertEquals(1, second.completedWords)

        val third = highlight(500)
        assertEquals(2, third.wordIndex)
        assertEquals(0f, third.wordProgress, 1e-5f)
        assertEquals(2, third.completedWords)

        val done = highlight(900)
        assertEquals(2, done.wordIndex)
        assertEquals(1f, done.wordProgress, 1e-5f)
        assertEquals(3, done.completedWords)

        assertEquals(3, highlight(10_000).completedWords)
    }

    @Test
    fun `a line without timed words has no highlight`() {
        val line = PlayerLine(startMs = 0, endMs = 500, text = "plain", words = emptyList())
        val highlight = LyricsPlayerTimeline.wordHighlight(line, 100)
        assertFalse(highlight.hasWords)
        assertEquals(WordHighlight.NONE, highlight)
    }

    @Test
    fun `clock starts from the first host sample and adds the animation head start`() {
        val clock = LyricsPlayerClock()
        clock.update(hostPositionMs = null, hostPlaying = true, nowNanos = 0)
        clock.update(hostPositionMs = 1_000, hostPlaying = true, nowNanos = 0)
        assertEquals(1_000L, clock.positionMs)
        assertEquals(1_100L, clock.visualPositionMs())
        assertTrue(clock.isPlaying)
    }

    @Test
    fun `clock extrapolates between samples while playing`() {
        val clock = LyricsPlayerClock()
        clock.update(1_000, true, 0)
        clock.update(1_500, true, 500_000_000)
        assertEquals(1_500L, clock.positionMs)
        assertEquals(1_600L, clock.visualPositionMs())
    }

    @Test
    fun `clock freezes while paused and drops the head start`() {
        val clock = LyricsPlayerClock()
        clock.update(1_000, true, 0)
        clock.update(1_500, false, 500_000_000)
        assertEquals(1_500L, clock.positionMs)
        assertEquals(1_500L, clock.visualPositionMs())
        clock.update(1_500, false, 1_500_000_000)
        assertEquals(1_500L, clock.positionMs)
    }

    @Test
    fun `clock snaps on a seek instead of drifting back`() {
        val clock = LyricsPlayerClock()
        clock.update(1_000, true, 0)
        clock.update(200, true, 2_000_000_000)
        assertEquals(200L, clock.positionMs)
        clock.update(1_000, true, 4_000_000_000)
        assertEquals(1_000L, clock.positionMs)
    }

    @Test
    fun `clock only folds a quarter of the jitter into the position`() {
        val clock = LyricsPlayerClock()
        clock.update(1_000, true, 0)
        // The controller reports 10 ms ahead of the extrapolation; the clock corrects by 2.5 ms.
        clock.update(1_110, true, 100_000_000)
        assertTrue("position=" + clock.positionMs, clock.positionMs in 1_100L..1_105L)
    }

    @Test
    fun `a null host sample re-seeds the clock on the next real one`() {
        val clock = LyricsPlayerClock()
        clock.update(1_000, true, 0)
        clock.update(null, true, 100_000_000)
        clock.update(5_000, true, 200_000_000)
        assertEquals(5_000L, clock.positionMs)
        clock.reset()
        assertEquals(0L, clock.positionMs)
        assertFalse(clock.isPlaying)
    }
}
