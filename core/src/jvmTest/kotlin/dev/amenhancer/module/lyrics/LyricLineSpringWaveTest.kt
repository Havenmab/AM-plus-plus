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
    private class Row(val name: String, var top: Int) {
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
            // The real host reads the row's own layout top; the fake row is the same thing.
            layoutTop = { row -> row.top },
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

    // ---- target correction: a later move moves the end point, never adds an impulse ------------

    @Test
    fun `a line change and the background line's growth are one monotone up move`() {
        // iOS semantics: the block travels up once, and the end point of that travel already
        // contains the background line's extra height. 357 px up, then the bg line grows 40 px
        // 50 ms later: the row must end 317 px above where it started, and its visual position must
        // never move down on the way.
        val row = Row("a", top = 1_000)
        val wave = wave()
        val first = wave.push(row, 357f, row.top, tempo())
        assertFalse(first.moving)
        assertEquals(357f, row.translationY, 1e-3f)
        val initialVisual = row.top + row.translationY

        repeat(3) { wave.advance(16.6f) } // ~50 ms
        val before = wave.lagOf(row)
        assertTrue("lag did not move: " + before, before < 357f)

        // The host lays the row out again: its top moves down by the bg line's height, and the
        // move it offers folds the current translation in, i.e. the new lag is `before - 40`.
        row.top += 40
        val applied = wave.push(row, before - 40f, row.top, tempo())
        assertTrue(applied.moving)
        assertEquals(40f, applied.layoutDeltaPx, 0.01f)
        // The correction did not move the row on screen: layout +40, translation -40.
        assertEquals(before - 40f, row.translationY, 0.01f)

        var previous = row.top + row.translationY
        var frames = 0
        while (wave.advance(16.6f)) {
            val visual = row.top + row.translationY
            assertTrue("went back down: " + previous + " -> " + visual, visual <= previous + 1e-3f)
            previous = visual
            frames += 1
            assertTrue("never settles", frames < 4_000)
        }
        // 357 px of travel, shortened by the 40 px the row was moved instead, so the whole line
        // change is one up move of 317 px whose end point already contains the grow.
        assertEquals(317f, initialVisual - (row.top + row.translationY), 0.5f)
        assertEquals(2, row.finished)
    }

    @Test
    fun `a correction inside the stagger slot keeps the slot and the start position`() {
        val first = Row("a", top = 200)
        val second = Row("b", top = 320)
        val wave = wave()
        wave.push(first, -300f, first.top, tempo())
        wave.push(second, -300f, second.top, tempo())
        wave.advance(16.6f)
        val slot = wave.delayOf(second)
        assertTrue("second should still be waiting, slot=" + slot, slot > 0f)
        val before = second.translationY
        val visualBefore = second.top + second.translationY

        // Its layout moves by -40 (the bg line collapsing) while it has not started moving.
        second.top -= 40
        val applied = wave.push(second, before + 40f, second.top, tempo())
        assertTrue(applied.waiting)
        assertTrue(applied.moving)
        assertEquals(0f, applied.keptVelocity, 0f)
        assertEquals(-40f, applied.layoutDeltaPx, 0.01f)
        // Still in the same slot: a correction never re-staggers a row, and this push consumed no
        // frame (the slot only drains inside advance).
        assertEquals(slot, wave.delayOf(second), 0.01f)
        assertEquals(visualBefore, second.top + second.translationY, 0.02f)

        wave.settle()
        assertEquals(0f, second.translationY, 0f)
    }

    @Test
    fun `a correction that moves the end point past the row drops the velocity`() {
        // The row is travelling up with real velocity when a later layout puts the end point on the
        // *other* side of it (a background line bigger than the remaining lag). Keeping that
        // velocity would carry the row away from home and bounce it back; the wave drops it instead.
        val row = Row("a", top = 1_000)
        val wave = wave()
        wave.push(row, 200f, row.top, tempo())
        repeat(2) { wave.advance(16.6f) }
        val lag = wave.lagOf(row)
        val velocity = wave.velocityOf(row)
        assertTrue("expected an upward velocity, got " + velocity, velocity < 0f)

        row.top += 300 // a bigger growth than the remaining lag
        val applied = wave.push(row, lag - 300f, row.top, tempo())
        assertTrue(applied.moving)
        assertTrue("velocity should have been dropped", applied.velocityDropped)
        assertEquals(0f, applied.keptVelocity, 0f)
        assertTrue("the end point flipped past the row", applied.lagPx < 0f)

        // No overshoot and no reversal: the lag only ever grows towards 0 from below.
        var previous = row.translationY
        var frames = 0
        while (wave.advance(16.6f)) {
            val visual = row.translationY
            assertTrue("reversed: " + previous + " -> " + visual, visual >= previous - 1e-3f)
            assertTrue("overshot past zero: " + visual, visual <= 0f)
            previous = visual
            frames += 1
            assertTrue("never settles", frames < 4_000)
        }
        assertEquals(0f, row.translationY, 0f)
    }

    @Test
    fun `alternating background line corrections converge on the layout position`() {
        val row = Row("a", top = 1_000)
        val wave = wave()
        wave.push(row, 240f, row.top, tempo())
        repeat(4) { wave.advance(16.6f) }

        // grow, shrink, grow: each is a target correction of the same row.
        var frames = 0
        listOf(40f, -40f, 40f, -40f).forEach { delta ->
            val lag = wave.lagOf(row)
            row.top += delta.toInt()
            val applied = wave.push(row, lag - delta, row.top, tempo())
            assertTrue(applied.moving)
            assertEquals(delta, applied.layoutDeltaPx, 0.01f)
            repeat(3) {
                wave.advance(16.6f)
                frames += 1
                assertTrue("unbounded", frames < 4_000)
            }
            // Never further from home than the line-change displacement itself.
            assertTrue("lag ran away: " + wave.lagOf(row), kotlin.math.abs(wave.lagOf(row)) <= 240f)
        }
        wave.settle()
        assertEquals(0f, row.translationY, 0f)
        // One finish per move the host started: the first and each of the four corrections.
        assertEquals(5, row.finished)
    }

    @Test
    fun `a first move reports itself as a new row, not a correction`() {
        val row = Row("a", top = 500)
        val wave = wave()
        val applied = wave.push(row, -120f, row.top, tempo())
        assertFalse(applied.moving)
        assertFalse(applied.waiting)
        assertFalse(applied.velocityDropped)
        assertEquals(0f, applied.previousLagPx, 0f)
        assertEquals(-120f, applied.lagPx, 0f)
        assertEquals(120f, applied.layoutDeltaPx, 0f) // 0 - (-120): the layout moved up 120
    }

    // ---- per-frame layout tracking: a growth that never announces itself ------------------------

    @Test
    fun `a background line growing frame by frame is absorbed without a downward frame`() {
        // The host grows the background line from a ValueAnimator that rewrites layoutParams.height
        // once per frame, with no adapter notification: no animateMove, no dy. Only the row's own
        // top shows it. 12 frames x 4 px = 48 px, i.e. the whole background line.
        val above = Row("above", top = 200)
        val below = Row("below", top = 400)
        val wave = wave()
        wave.push(above, 240f, above.top, tempo())
        wave.push(below, 240f, below.top, tempo())
        val startVisual = below.top + below.translationY
        assertEquals(240f, startVisual - below.top, 1e-3f)

        var previous = startVisual
        repeat(12) {
            below.top += 4
            wave.advance(16.6f)
            val visual = below.top + below.translationY
            assertTrue("went back down: " + previous + " -> " + visual, visual <= previous + 1e-3f)
            previous = visual
        }
        // 48 px of the 240 were taken out of the travel, not added to it.
        assertTrue("absorbed nothing?", startVisual - previous > 40f)

        var frames = 0
        while (wave.advance(16.6f)) {
            val visual = below.top + below.translationY
            assertTrue("went back down while settling", visual <= previous + 1e-3f)
            previous = visual
            frames += 1
            assertTrue("never settles", frames < 4_000)
        }
        assertEquals(0f, below.translationY, 0f)
        assertEquals(240f - 48f, startVisual - (below.top + below.translationY), 1f)
    }

    @Test
    fun `a shift every row takes part in is a scroll and is not absorbed`() {
        // A drag or a re-anchor moves every child by the same amount. Compensating that would glue
        // the block to the screen under the user's finger, so the wave only re-baselines.
        val first = Row("a", top = 200)
        val second = Row("b", top = 320)
        val wave = wave()
        wave.push(first, 240f, first.top, tempo())
        wave.push(second, 240f, second.top, tempo())
        wave.advance(16.6f)
        // The second row is still inside its 50 ms slot, so its translation cannot move at all.
        val visualBefore = second.top + second.translationY

        first.top += 40
        second.top += 40
        wave.advance(16.6f)
        assertEquals(40f, (second.top + second.translationY) - visualBefore, 0.01f)
        // Not absorbed at all: the row keeps the translation the move gave it.
        assertEquals(240f, second.translationY, 1e-3f)
    }

    @Test
    fun `a move and a frame shift for the same layout are not counted twice`() {
        val row = Row("a", top = 500)
        val wave = wave()
        wave.push(row, 240f, row.top, tempo())
        wave.advance(16.6f)
        val lag = wave.lagOf(row)

        // The layout moves 40 px *and* announces itself with a move that carries the new top.
        row.top += 40
        val applied = wave.push(row, lag - 40f, row.top, tempo())
        assertEquals(40f, applied.layoutDeltaPx, 0.01f)
        val afterPush = row.translationY

        // The next frame must only step the spring: no second 40 px were absorbed.
        wave.advance(16.6f)
        assertTrue(
            "absorbed twice: " + afterPush + " -> " + row.translationY,
            kotlin.math.abs(row.translationY - afterPush) < 20f,
        )
    }

    @Test
    fun `a shift beyond the bound re-baselines instead of dragging the row`() {
        val row = Row("a", top = 500)
        val wave = wave()
        wave.push(row, 120f, row.top, tempo())
        wave.advance(16.6f)
        val before = row.translationY

        row.top += 900 // a seek: far beyond one frame of a growing line
        wave.advance(16.6f)
        assertTrue(
            "absorbed a jump: " + before + " -> " + row.translationY,
            kotlin.math.abs(row.translationY - before) < 20f,
        )
    }
}