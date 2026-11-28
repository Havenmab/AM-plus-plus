package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LyricLineShiftSkip
import dev.amenhancer.module.lyrics.LyricLineSpringPolicy
import dev.amenhancer.module.lyrics.LyricLineSpringPush
import dev.amenhancer.module.lyrics.LyricLineSpringStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two lines this feature's device verdict is read from, and the rule that keeps them readable.
 *
 * A log is only evidence if its vocabulary is fixed and it cannot flood: `row-settle` is one line
 * per row that arrived, `animations-finished` one per line change (the frame the last row comes to
 * rest), and every event class is held to one line per window. Both are asserted here on a plain
 * JVM, with the clock and the log injected, so what a device run prints is what this test pins.
 */
class LyricLineSpringDiagnosticsTest {
    private class Capture {
        val lines = ArrayList<String>()
        var now = 1_000L

        fun diagnostics(): LyricLineSpringDiagnostics = LyricLineSpringDiagnostics(
            clock = { now },
            log = { line -> lines += line },
        )
    }

    @Test
    fun `a row that arrives prints its slot and whether the host took the call`() {
        val capture = Capture()
        val diagnostics = capture.diagnostics()

        diagnostics.rowSettle(3, ok = true)
        diagnostics.rowSettle(4, ok = false)

        // The second one is inside the same 500 ms window: a line change settles 4-8 rows at once,
        // and only the leading ones may be printed.
        assertEquals(listOf("row-settle holder=3 ok=1"), capture.lines)

        capture.now += 600
        diagnostics.rowSettle(4, ok = false)
        assertEquals("row-settle holder=4 ok=0", capture.lines.last())
    }

    @Test
    fun `the borrowed answer is handed back once per line change`() {
        val capture = Capture()
        val diagnostics = capture.diagnostics()

        diagnostics.animationsFinished(ok = true, borrows = 12)
        assertEquals("animations-finished ok=1 borrows=12", capture.lines.single())

        // Same window, next line change 400 ms later: suppressed, on purpose.
        capture.now += 400
        diagnostics.animationsFinished(ok = true, borrows = 9)
        assertEquals(1, capture.lines.size)

        // A second later the next hand-back is printed, with its own count.
        capture.now += 600
        diagnostics.animationsFinished(ok = false, borrows = 7)
        assertEquals("animations-finished ok=0 borrows=7", capture.lines.last())
    }

    @Test
    fun `a missing role is reported once, not once per line change`() {
        val capture = Capture()
        val diagnostics = capture.diagnostics()

        diagnostics.rowSettleUnavailable("methods.rowSettle: no such method")
        diagnostics.rowSettleUnavailable("methods.rowSettle: no such method")
        diagnostics.animationsFinishedUnavailable("methods.finishDispatch: no such method")

        assertEquals(
            listOf(
                "row-settle unavailable: methods.rowSettle: no such method",
                "animations-finished unavailable: methods.finishDispatch: no such method",
            ),
            capture.lines,
        )
    }

    /**
     * The gate's sentinel is the one thing that can silence the whole vocabulary at once: with
     * \`Long.MIN_VALUE\` as "never printed", \`now - last\` overflowed to a negative difference and the
     * first line of **every** class was suppressed for the entire session.
     */
    @Test
    fun `the first line of every class goes through`() {
        val capture = Capture()
        val diagnostics = capture.diagnostics()
        val status = { LyricLineSpringStatus(1, 1, 0, 12f, listOf(0f)) }
        val holder = Any()

        diagnostics.push(
            12,
            120,
            LyricLineSpringPush(
                lagPx = -12f,
                previousLagPx = 0f,
                offeredVelocity = 0f,
                keptVelocity = 0f,
                moving = false,
                waiting = false,
            ),
            LyricLineSpringPolicy.tempo,
            1,
            status,
        )
        diagnostics.waveStarted(status)
        diagnostics.tick(7, status)
        diagnostics.lineChange(3, 5)
        diagnostics.stuck(2, 30f, 400f)
        diagnostics.cleaned("endAnimation", LyricLineSpringReset(1, 4f))
        diagnostics.layoutShift(holder, 4, 8f)
        diagnostics.shiftIgnored(33, LyricLineShiftSkip.TOO_FAST)
        diagnostics.skipped(holder, 0.4f, 12f, 200f)
        diagnostics.idle { "waves=1 rows=2 maxLag=12.0" }
        diagnostics.rowSettle(2, ok = true)
        diagnostics.animationsFinished(ok = true, borrows = 3)
        diagnostics.refused("dx=8 has a horizontal component")
        diagnostics.traceUnavailable("no role")
        diagnostics.unavailable("incomplete")
        diagnostics.failed("frame", IllegalStateException("boom"))

        listOf(
            "push ", "start ", "tick ", "line-change ", "stuck ", "reset ", "layout-shift ",
            "ignore-shift ", "skip? ", "idle ", "row-settle ", "animations-finished ",
            "handed back: ", "line-change trace unavailable: ", "line spring unavailable: ",
            "line spring frame failed: ",
        ).forEach { prefix ->
            assertTrue(prefix + " never printed: " + capture.lines, capture.lines.any { it.startsWith(prefix) })
        }
    }

    @Test
    fun `a whole session cannot flood the log`() {
        val capture = Capture()
        val diagnostics = capture.diagnostics()

        repeat(200) {
            capture.now += 1_000
            diagnostics.animationsFinished(ok = true, borrows = it)
            diagnostics.rowSettle(it, ok = true)
        }

        // One line per second for a minute or two of lyrics: bounded, and the two classes never
        // starve each other because each has its own budget.
        assertTrue(capture.lines.size < 120)
        assertTrue(capture.lines.any { it.startsWith("row-settle ") })
        assertTrue(capture.lines.any { it.startsWith("animations-finished ") })
    }
}
