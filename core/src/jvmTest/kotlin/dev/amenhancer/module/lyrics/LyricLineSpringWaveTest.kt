package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.LyricLineSpringFeel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping half of 「换行弹簧」: what a row is told to do, when, and how many times.
 *
 * RecyclerView's pending-move ledger takes exactly one "this animation finished" per started move,
 * so the tests below count finished callbacks against a fake row rather than asserting "the flag is
 * set somewhere". The wave's other half — AMLL's stagger — is asserted as delays and as frames the
 * rows did not move.
 */
class LyricLineSpringWaveTest {
    /** Stands in for a RecyclerView.ViewHolder: its itemView's translationY and its finish count. */
    private class Row(val name: String, val top: Int) {
        var translationY = 0f
        var finished = 0
        override fun toString(): String = name + "@" + top
    }

    private fun tempo(): LyricLineSpringTempo = requireNotNull(
        LyricLineSpringPolicy.tempoFor(LyricLineSpringFeel.AMLL, 600L),
    )

    private fun wave(paneHeightPx: Int = 0): LyricLineSpringWave<Row> =
        LyricLineSpringWave<Row>(
            write = { row, offset -> row.translationY = offset },
            finish = { row -> row.finished += 1 },
        ).also { it.paneHeightPx = paneHeightPx }

    private fun LyricLineSpringWave<Row>.settle(limit: Int = 4_000): Int {
        var frames = 0
        while (advance(16.6f)) {
            frames += 1
            assertTrue("never settles", frames < limit)
        }
        return frames
    }

    @Test
    fun `a line change starts every row at the same lag and they leave in visual order`() {
        val rows = (0 until 4).map { Row("r" + it, top = 300 + it * 120) }
        val wave = wave(paneHeightPx = 1_000)
        rows.forEach { wave.push(it, -240f, it.top, tempo()) }

        rows.forEach { assertEquals(-240f, it.translationY, 1e-3f) }
        assertEquals(4, wave.size)
        assertTrue(wave.isRunning)

        // One frame: only the row above the 35 % focus line moves. Its neighbours are still
        // waiting for their own 50 ms slot, which is the whole point of the wave.
        wave.advance(16.6f)
        assertTrue(rows[0].translationY > -240f)
        assertEquals(-240f, rows[1].translationY, 0f)
        assertEquals(-240f, rows[3].translationY, 0f)
    }

    @Test
    fun `the stagger is 50 ms and tightens by 1 point 05 past the focus line`() {
        assertEquals(0f, LyricLineStaggerPolicy.delayMs(0, 0), 1e-4f)
        assertEquals(50f, LyricLineStaggerPolicy.delayMs(1, 0), 1e-4f)
        assertEquals(50f + 50f / 1.05f, LyricLineStaggerPolicy.delayMs(2, 0), 0.01f)
        assertEquals(
            50f + 50f / 1.05f + 50f / 1.05f / 1.05f,
            LyricLineStaggerPolicy.delayMs(3, 0),
            0.01f,
        )
        // Nothing past the focus line: every row is an even 50 ms behind the one above it.
        assertEquals(150f, LyricLineStaggerPolicy.delayMs(3, 99), 1e-4f)
        // The slope really is 1/1.05 per row, not a linear ramp.
        val third = LyricLineStaggerPolicy.delayMs(3, 0) - LyricLineStaggerPolicy.delayMs(2, 0)
        assertEquals(50f / 1.05f / 1.05f, third, 0.01f)
    }

    @Test
    fun `the focus line is the first row at or below 35 percent of the pane`() {
        val tops = listOf(100, 160, 220, 280)
        // 0.35 * 500 = 175: the third row is the first one on the line.
        assertEquals(2, LyricLineStaggerPolicy.focusRank(tops, 500))
        // 0.35 * 1000 = 350: no row reaches it, so nothing is narrowed.
        assertEquals(4, LyricLineStaggerPolicy.focusRank(tops, 1_000))
        // An unmeasured pane cannot narrow.
        assertEquals(4, LyricLineStaggerPolicy.focusRank(tops, 0))
        assertEquals(0, LyricLineStaggerPolicy.focusRank(emptyList(), 500))
    }

    @Test
    fun `a row whose delay has not run out does not move`() {
        val row = Row("a", top = 200)
        val wave = wave()
        wave.push(row, -300f, row.top, tempo())
        // Ranked into the second slot: 50 ms, i.e. three whole frames before it may start.
        val second = Row("b", top = 320)
        wave.push(second, -300f, second.top, tempo())
        wave.advance(16.6f)
        assertEquals(-300f, second.translationY, 0f)
        wave.advance(16.6f)
        assertEquals(-300f, second.translationY, 0f)
        assertEquals(50f - 33.2f, wave.delayOf(second), 0.1f)
        wave.advance(16.6f)
        // Its slot is spent; the next frame is the first one that moves it.
        wave.advance(16.6f)
        assertTrue(second.translationY > -300f)
        assertEquals(0f, wave.delayOf(second), 0f)
    }

    @Test
    fun `a re-target keeps the row's velocity and its accumulated lag`() {
        val row = Row("a", top = 200)
        val wave = wave()
        wave.push(row, -300f, row.top, tempo())
        wave.advance(16.6f)
        wave.advance(16.6f)
        wave.advance(16.6f)
        val carried = wave.velocityOf(row)
        assertTrue("carried=" + carried, carried > 0f)

        // The next line change: the row is still in flight, so it must not wait again.
        wave.push(row, -420f, row.top, tempo())
        assertEquals(-420f, row.translationY, 1e-3f)
        assertEquals(carried, wave.velocityOf(row), kotlin.math.abs(carried) * 0.01f)
        assertEquals(0f, wave.delayOf(row), 0f)
        wave.advance(16.6f)
        assertTrue(row.translationY > -420f)

        // Two moves taken over: the retired one was finished, the current one is owed.
        assertEquals(1, row.finished)
        wave.settle()
        assertEquals(0f, row.translationY, 0f)
        assertEquals(2, row.finished)
    }

    @Test
    fun `three line changes of six rows dispatch exactly eighteen finishes`() {
        val rows = (0 until 6).map { Row("r" + it, top = 200 + it * 120) }
        val wave = wave()
        repeat(3) {
            rows.forEach { row -> wave.push(row, -240f, row.top, tempo()) }
            repeat(4) { wave.advance(16.6f) }
        }
        // Each line change retired the previous move of all six rows: 2 * 6 so far.
        assertEquals(12, rows.sumOf { it.finished })

        wave.settle()
        rows.forEach {
            assertEquals(0f, it.translationY, 0f)
            assertEquals(3, it.finished)
        }
        assertEquals(18, rows.sumOf { it.finished })
        assertFalse(wave.isRunning)
    }

    @Test
    fun `every row of three separate line changes ends at zero with one finish each`() {
        val wave = wave()
        val all = ArrayList<Row>()
        repeat(3) { batch ->
            val rows = (0 until 6).map { Row("b" + batch + "r" + it, top = 200 + it * 120) }
            all += rows
            rows.forEach { row -> wave.push(row, -240f, row.top, tempo()) }
            // Settle one line change before the next one, i.e. a slow song.
            wave.settle()
        }
        assertEquals(18, all.size)
        all.forEach {
            assertEquals(0f, it.translationY, 0f)
            assertEquals(1, it.finished)
        }
        assertFalse(wave.isRunning)
    }

    @Test
    fun `endAnimations mid flight zeroes every row and finishes it exactly once`() {
        val rows = (0 until 6).map { Row("r" + it, top = 200 + it * 120) }
        val wave = wave()
        rows.forEach { wave.push(it, -240f, it.top, tempo()) }
        wave.advance(50f)
        assertTrue(rows.any { it.translationY != -240f })

        wave.finishAll()
        rows.forEach {
            assertEquals(0f, it.translationY, 0f)
            assertEquals(1, it.finished)
        }
        assertFalse(wave.isRunning)

        // A second reset (the host may cancel twice) is a no-op, not a second finish.
        wave.finishAll()
        wave.advance(16.6f)
        rows.forEach { assertEquals(1, it.finished) }
    }

    @Test
    fun `endAnimation ends one row and leaves the others travelling`() {
        val a = Row("a", top = 200)
        val b = Row("b", top = 320)
        val wave = wave()
        wave.push(a, -300f, a.top, tempo())
        wave.push(b, -300f, b.top, tempo())
        wave.advance(16.6f)
        val beforeB = b.translationY

        assertTrue(wave.finishHolder(a))
        assertEquals(0f, a.translationY, 0f)
        assertEquals(1, a.finished)
        assertFalse(wave.isPending(a))
        // The other row keeps moving (once its own slot arrives) and the ended one is never
        // written again.
        repeat(6) { wave.advance(16.6f) }
        assertEquals(0f, a.translationY, 0f)
        assertTrue("b=" + b.translationY + " before=" + beforeB, b.translationY > beforeB)

        assertFalse(wave.finishHolder(a))
        assertEquals(1, a.finished)
        assertFalse(wave.contains(Row("zzz", 0)))
    }

    @Test
    fun `a row joining mid flight is written the lag it arrived with`() {
        val a = Row("a", top = 200)
        val wave = wave()
        wave.push(a, -300f, a.top, tempo())
        repeat(5) { wave.advance(16.6f) }
        val lagA = a.translationY
        assertTrue("lagA=" + lagA, lagA > -300f && lagA < 0f)

        // A new holder appears while the first is still moving: its own lag, written at once, and
        // it must not disturb the row already travelling.
        val b = Row("b", top = 320)
        wave.push(b, -120f, b.top, tempo())
        assertEquals(-120f, b.translationY, 1e-3f)
        assertEquals(lagA, a.translationY, 0f)

        wave.settle()
        assertEquals(0f, a.translationY, 0f)
        assertEquals(0f, b.translationY, 0f)
        assertEquals(1, a.finished)
        assertEquals(1, b.finished)
    }

    @Test
    fun `a long frame step is clamped so a stall cannot teleport a row`() {
        val stalled = Row("stalled", 200)
        val stepped = Row("stepped", 200)
        val stalledWave = wave().also { it.push(stalled, -240f, stalled.top, tempo()) }
        val steppedWave = wave().also { it.push(stepped, -240f, stepped.top, tempo()) }

        stalledWave.advance(5_000f)
        steppedWave.advance(LyricLineSpringPolicy.MAX_FRAME_STEP_MS)

        assertEquals(stepped.translationY, stalled.translationY, 0f)
        // 100 ms of spring, not 5 s: the row has moved but is nowhere near its layout position.
        assertTrue("lag=" + stalled.translationY, stalled.translationY in -200f..-100f)
    }

    @Test
    fun `joining twice inside one line change costs one finish and keeps one member`() {
        val row = Row("a", top = 200)
        val wave = wave()
        wave.push(row, -240f, row.top, tempo())
        wave.push(row, -240f, row.top, tempo())
        assertEquals(1, wave.size)
        // The first push was followed by a second one before the row ever started moving, so the
        // host started two moves: the first one is finished here.
        assertEquals(1, row.finished)

        wave.advance(16.6f)
        wave.settle()
        assertEquals(1, wave.size)
        assertEquals(0f, row.translationY, 0f)
        assertEquals(2, row.finished)
    }
}
