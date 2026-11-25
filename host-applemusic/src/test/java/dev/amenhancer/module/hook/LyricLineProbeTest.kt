package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineProbeTest {
    private val lines = mutableListOf<String>()
    private var now = 1_000L
    private val probe = LyricLineProbe(sink = { lines += it }, clock = { now })

    private fun state(
        queue: Int = 0,
        scrolled: Int = 0,
        deferred: Int = 0,
        anchored: Int = 0,
    ) = LyricLineProbeState(queue = queue, scrolled = scrolled, deferred = deferred, anchored = anchored)

    @Test
    fun `every emitted line names its event`() {
        probe.lineChange(
            start = 12, end = 12, firstVisible = 9, lastVisible = 18,
            state = state(), layoutManager = null,
        )
        now += 500L
        probe.anchor(12, 64)
        probe.animateMove(
            position = 12, fromY = 0, toY = 0, translationY = 0, dx = 0, dy = 0,
            state = state(), animated = false,
        )
        probe.flushStarted(state())
        probe.flushFinished(state(), ran = 3, durationMs = 2L)
        probe.rowTransition("A", 12, "q8.m9", true, 1f, 1f, 1f)

        assertEquals(6, lines.size)
        assertTrue(lines.all { it.startsWith("event=") })
        assertTrue(lines.any { it.startsWith("event=lineChange ") })
        assertTrue(lines.any { it.startsWith("event=anchor ") })
        assertTrue(lines.any { it.startsWith("event=animateMove ") })
        assertEquals(2, lines.count { it.startsWith("event=flush ") })
        assertTrue(lines.any { it.startsWith("event=rowAnim ") })
    }

    @Test
    fun `the line change reports the sentinel the re-anchor and the flags`() {
        probe.lineChange(
            start = 12, end = 12, firstVisible = 9, lastVisible = 18,
            state = LyricLineProbeState(queue = 2, scrolled = 1, deferred = 1, anchored = 0),
            layoutManager = null,
        )
        probe.lineChange(
            start = -1, end = -1, firstVisible = null, lastVisible = null,
            state = LyricLineProbeState(queue = 0, scrolled = 0, deferred = 0, anchored = 1),
            layoutManager = null,
        )

        val change = lines[0]
        assertTrue(change.contains("start=12"))
        assertTrue(change.contains("end=12"))
        assertTrue(change.contains("sentinel=0"))
        // A real line change is exactly what makes the host re-anchor the list.
        assertTrue(change.contains("anchor=1"))
        assertTrue(change.contains("first=9"))
        assertTrue(change.contains("last=18"))
        assertTrue(change.contains("queue=2"))
        assertTrue(change.contains("T0=1"))
        assertTrue(change.contains("U0=1"))
        assertTrue(change.contains("M0=0"))

        // The host's own "no current line" value suppresses the re-anchor.
        assertTrue(lines[1].contains("sentinel=1"))
        assertTrue(lines[1].contains("anchor=0"))
    }

    @Test
    fun `a role that did not resolve renders as not available`() {
        probe.animateMove(
            position = null, fromY = 0, toY = 0, translationY = 0, dx = 0, dy = 0,
            state = LyricLineProbeState(), animated = null,
        )

        val line = lines.single()
        assertTrue(line.contains("pos=n/a"))
        assertTrue(line.contains("ret=n/a"))
        assertTrue(line.contains("queue=n/a"))
        assertTrue(line.contains("zero=0"))
    }

    @Test
    fun `the move line separates a zero displacement move from a real one`() {
        probe.animateMove(
            position = 3, fromY = 120, toY = 60, translationY = -8, dx = 0, dy = -68,
            state = state(queue = 1), animated = true,
        )
        probe.animateMove(
            position = 4, fromY = 60, toY = 60, translationY = 8, dx = 4, dy = -8,
            state = state(queue = 2), animated = false,
        )

        assertTrue(lines[0].contains("ret=1"))
        assertTrue(lines[0].contains("zero=0"))
        assertTrue(lines[1].contains("ret=0"))
        // The host recomputes dy as toY - (fromY + translationY); the probe repeats that arithmetic.
        assertTrue(lines[1].contains("dy=-8"))
        assertTrue(lines[1].contains("zero=1"))
    }

    @Test
    fun `each event is capped at ten lines per second and the rest is counted`() {
        repeat(12) { probe.anchor(it, 0) }

        assertEquals(LyricLineProbe.MAX_LINES_PER_WINDOW, lines.size)
        assertFalse(lines.any { it.contains("suppressed=") })

        now += LyricLineProbe.WINDOW_MS
        probe.anchor(99, 0)

        assertEquals(LyricLineProbe.MAX_LINES_PER_WINDOW + 1, lines.size)
        assertTrue(lines.last().contains("suppressed=2"))
    }

    @Test
    fun `one window of line changes yields one summary at the next change`() {
        // -1 is the host's "no current line" value: it must not count as a change.
        now += 2_000L
        probe.lineChangeStarted()
        probe.lineChange(
            start = -1, end = -1, firstVisible = null, lastVisible = null,
            state = state(), layoutManager = null,
        )
        repeat(LyricLineProbe.SUMMARY_EVERY_CHANGES + 1) { index ->
            now += 2_000L
            probe.lineChangeStarted()
            probe.lineChange(
                start = index,
                end = index,
                firstVisible = null,
                lastVisible = null,
                state = state(queue = 1),
                layoutManager = null,
            )
            probe.animateMove(
                position = index, fromY = 0, toY = 0, translationY = 0, dx = 0, dy = 0,
                state = state(queue = 1), animated = false,
            )
            probe.animateMove(
                position = index + 1, fromY = 0, toY = 0, translationY = 0, dx = 0, dy = 0,
                state = state(queue = 2), animated = false,
            )
            probe.rowTransition("A", index, "q8.m9", true, 1f, 1f, 1f)
            now += 100L
            probe.flushStarted(state(queue = 2, deferred = 1))
            probe.flushFinished(state(queue = 0), ran = 2, durationMs = 1L)
        }

        val summary = lines.single { it.startsWith("event=summary ") }
        assertTrue(summary.contains("changes=20"))
        assertTrue(summary.contains("sentinel=1"))
        assertTrue(summary.contains("moves=40"))
        assertTrue(summary.contains("moves/change=2.00"))
        assertTrue(summary.contains("zeroDx=true"))
        assertTrue(summary.contains("animEntry=20"))
        assertTrue(summary.contains("animText=0"))
        assertTrue(summary.contains("s2Delay=100ms"))
    }

    @Test
    fun `a text row in the row transition makes the summary say so`() {
        repeat(LyricLineProbe.SUMMARY_EVERY_CHANGES + 1) {
            now += 2_000L
            probe.lineChangeStarted()
            probe.lineChange(
                start = 1, end = 1, firstVisible = null, lastVisible = null,
                state = state(), layoutManager = null,
            )
            probe.rowTransition("A", 1, "q8.o9", false, 1f, 1f, 1f)
        }

        val summary = lines.single { it.startsWith("event=summary ") }
        assertTrue(summary.contains("animEntry=20"))
        assertTrue(summary.contains("animText=20"))
        // No move at all is not evidence that moves are zero displacement.
        assertTrue(summary.contains("zeroDx=n/a"))
    }

    @Test
    fun `only the lyrics list manager is reported`() {
        val lyricsManager = Any()
        val otherManager = Any()
        probe.lineChange(
            start = 1, end = 1, firstVisible = null, lastVisible = null,
            state = state(), layoutManager = lyricsManager,
        )
        lines.clear()

        assertTrue(probe.isLyricsLayoutManager(lyricsManager, null))
        assertFalse(probe.isLyricsLayoutManager(otherManager, null))
        assertFalse(probe.isLyricsLayoutManager(null, String::class.java))
        assertTrue(probe.isLyricsLayoutManager("text", String::class.java))
    }

    @Test
    fun `the session stops on its own budget`() {
        val sink = mutableListOf<String>()
        var clock = 0L
        val probe = LyricLineProbe(sink = { sink += it }, clock = { clock })
        repeat(LyricLineProbe.MAX_LINES * 2) {
            clock += LyricLineProbe.WINDOW_MS
            probe.anchor(it, 0)
        }

        assertEquals(LyricLineProbe.MAX_LINES, sink.size)
    }
}
