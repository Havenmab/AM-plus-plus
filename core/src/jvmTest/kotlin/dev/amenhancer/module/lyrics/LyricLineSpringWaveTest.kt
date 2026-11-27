package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        /** How many moves the wave started on this row (dispatchMoveStarting). */
        var started = 0

        /** Where the eye sees the row: its layout position plus its translation. */
        fun visual(): Float = top + translationY
        override fun toString(): String = name + "@" + top
    }

    private fun tempo(): LyricLineSpringTempo = LyricLineSpringPolicy.tempo

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

        // One move: the correction re-aimed it, so nothing was retired and nothing is owed yet.
        assertEquals(0, row.finished)
        wave.settle()
        assertEquals(0f, row.translationY, 0f)
        assertEquals(1, row.finished)
    }

    @Test
    fun `three line changes of six rows dispatch one move per row, not one per change`() {
        val rows = (0 until 6).map { Row("r" + it, top = 200 + it * 120) }
        val wave = wave()
        repeat(3) {
            rows.forEach { row -> wave.push(row, -240f, row.top, tempo()) }
            repeat(4) { wave.advance(16.6f) }
        }
        // The second and third line changes re-aimed the move the first one started; retiring it
        // would tell RecyclerView the row is idle while it is still travelling (a fast song does
        // that every 60-120 ms, which is what swallows the animation on the device).
        assertEquals(0, rows.sumOf { it.finished })

        wave.settle()
        rows.forEach {
            assertEquals(0f, it.translationY, 0f)
            assertEquals(1, it.finished)
        }
        assertEquals(6, rows.sumOf { it.finished })
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
        // The second push landed before the row ever moved, so it corrected the first one instead
        // of starting a second move: one row, one move, one finish at the end.
        assertEquals(0, row.finished)

        wave.advance(16.6f)
        wave.settle()
        assertEquals(1, wave.size)
        assertEquals(0f, row.translationY, 0f)
        assertEquals(1, row.finished)
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
        // One continuous move: the correction re-aimed it instead of ending it.
        assertEquals(1, row.finished)
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
        // One move the whole way: the four corrections re-aimed it, so one finish is owed.
        assertEquals(1, row.finished)
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

    // ---- a fast song: rapid line changes must not swallow the wave -------------------------------

    /** What one line change of [fastSong] did, for the assertions below. */
    private class Batch(
        val dy: Int,
        /** True when the hook's takeover gate handed the whole batch back to the host. */
        val refused: Boolean,
        /** How far each row's visual position (top + translationY) travelled over the interval. */
        val movedPx: Map<Row, Float>,
        /** Rows still inside a stagger slot when the batch was ranked; they may legitimately wait. */
        val waiting: Set<Row>,
        /** Rows whose remaining wait grew across the push: the stagger was reset. */
        val resetWait: Set<Row>,
        /** Rows finished while still visibly displaced: RecyclerView was told the move was over. */
        val finishedWhileDisplaced: Set<Row>,
        /** The largest single-frame move *away* from where the row was heading, in px. */
        val backPx: Float,
    )

    /**
     * Replays a fast lyric song — one line change every [intervalMs], [batches] of them, each with a
     * different `dy` — through the wave *and* through the hook's takeover gate.
     *
     * The harness reproduces the host, not just the wave:
     *
     *  * the block's layout moves up by `dy` per line change, and the move the host offers folds
     *    the row's current translation in (`dy = toY - (fromY + translationY)`, 1626 `mc.y#m`), so
     *    the lag handed to the wave is `translationY + dy`: a row's residual therefore grows with
     *    the line rate even though every layout jump is still one line;
     *  * the gate the hook runs before it answers `false`
     *    ([LyricLineSpringPolicy.acceptsMove]) is applied exactly as the device applies it, and a
     *    refused move is *not* neutral: `mc.y#m` begins with `mc.y#x` -> `mc.y#e` on that row —
     *    "cancel this row's animation" — which the hook answers by zeroing the translation and
     *    finishing the row. That is the reported "the row jumps straight to its place".
     *
     * The window is short on purpose: 60/80/120 ms is 8-16 lines a second, i.e. the regime where
     * the 0.45 s spring never finishes a line before the next one lands.
     */
    private fun fastSong(
        intervalMs: Float,
        dys: List<Int>,
        batches: Int = 8,
        rowCount: Int = 6,
    ): List<Batch> {
        val rows = (0 until rowCount).map { Row("r" + it, top = 300 + it * 120) }
        val wave = wave(paneHeightPx = 1_000)
        val framesPerBatch = maxOf(1, Math.round(intervalMs / 16.6f))
        val out = ArrayList<Batch>()
        repeat(batches) { index ->
            val dy = dys[index % dys.size]
            val waitBefore = rows.associateWith { wave.delayOf(it) }
            val inWaveBefore = rows.associateWith { wave.contains(it) }
            val finishedBeforePush = rows.associateWith { it.finished }
            var refused = false
            rows.forEach { row ->
                val lag = row.translationY + dy
                row.top -= dy
                if (LyricLineSpringPolicy.acceptsMove(-dy, lag)) {
                    wave.push(row, lag, row.top, tempo())
                } else {
                    refused = true
                    wave.finishHolder(row, resetOffset = true)
                }
            }
            val moved = rows.associateWith { 0f }.toMutableMap()
            val finishedWhileDisplaced = HashSet<Row>()
            // The push itself must not end the move either: a "this animation is over" for a row
            // that is still displaced is what RecyclerView turns into a recycled, idle row.
            rows.forEach { row ->
                if (row.finished > finishedBeforePush.getValue(row) && abs(row.translationY) > 0.5f) {
                    finishedWhileDisplaced += row
                }
            }
            var waiting: Set<Row> = emptySet()
            var back = 0f
            var previous = rows.associateWith { it.top + it.translationY }
            repeat(framesPerBatch) { frame ->
                val finishedBefore = rows.associateWith { it.finished }
                wave.advance(16.6f)
                rows.forEach { row ->
                    val visual = row.top + row.translationY
                    moved[row] = moved.getValue(row) + abs(visual - previous.getValue(row))
                    // An upward line change only ever moves a row up: any frame that moves it back
                    // down is the "bounced back" look, or a stale spring fighting the new aim.
                    back = maxOf(back, visual - previous.getValue(row))
                    if (row.finished > finishedBefore.getValue(row) && abs(row.translationY) > 0.5f) {
                        finishedWhileDisplaced += row
                    }
                }
                previous = rows.associateWith { it.top + it.translationY }
                // The stagger is assigned by the first advance of the batch.
                if (frame == 0) waiting = rows.filter { wave.delayOf(it) > 0f }.toSet()
            }
            // A *reset*: a row that already existed in the wave was pushed back into a longer wait.
            // A brand new row receiving its first slot is not a reset, it is the stagger itself.
            val resetWait = rows.filter { row ->
                val grew = wave.delayOf(row) > waitBefore.getValue(row) + 1e-3f
                grew && (waitBefore.getValue(row) > 0f || inWaveBefore.getValue(row))
            }.toSet()
            out += Batch(dy, refused, moved, waiting, resetWait, finishedWhileDisplaced, back)
        }
        return out
    }

    @Test
    fun `a fast song keeps its moves and never hands a row back or finishes it mid travel`() {
        val dys = listOf(357, 261, 453, 400, 318)
        listOf(60f, 80f, 120f, 300f, 500f, 800f).forEach { interval ->
            fastSong(interval, dys).forEachIndexed { index, batch ->
                val where = "interval=" + interval + "ms batch=" + index + " dy=" + batch.dy + ": "
                assertFalse(
                    where + "the move was handed back to the host, i.e. the animation is swallowed",
                    batch.refused,
                )
                assertTrue(
                    where + "finished mid-travel: " + batch.finishedWhileDisplaced,
                    batch.finishedWhileDisplaced.isEmpty(),
                )
                assertTrue(
                    where + "the stagger was reset: " + batch.resetWait,
                    batch.resetWait.isEmpty(),
                )
                assertTrue(where + "went back down by " + batch.backPx + "px", batch.backPx <= 1e-2f)
            }
        }
    }

    @Test
    fun `every line change of a fast song still moves the rows it touched`() {
        val dys = listOf(357, 261, 453, 400, 318)
        listOf(60f, 80f, 120f, 300f, 500f, 800f).forEach { interval ->
            fastSong(interval, dys).forEachIndexed { index, batch ->
                batch.movedPx.forEach { (row, moved) ->
                    // A row that is still inside its stagger slot has not been given its turn yet.
                    if (row in batch.waiting) return@forEach
                    assertTrue(
                        "interval=" + interval + "ms batch=" + index + " " + row.name +
                            " moved only " + moved + "px: the line change was swallowed",
                        moved >= 1f,
                    )
                }
            }
        }
    }

    @Test
    fun `a correction smaller than a pixel is merged instead of re-aiming the spring`() {
        // Two moves whose dy cancels — the background line growing and collapsing inside one batch,
        // a re-anchor that lands where the row already is — leave a sub-pixel correction. Re-aiming
        // for that would cost the row its clock and its velocity for nothing, so it is merged and
        // only logged (LyricLineSpringDiagnostics.merged).
        val row = Row("a", top = 1_000)
        val wave = wave()
        wave.push(row, 240f, row.top, tempo())
        repeat(3) { wave.advance(16.6f) }
        val lag = wave.lagOf(row)
        val velocity = wave.velocityOf(row)
        val elapsed = wave.delayOf(row)
        assertTrue("expected an upward velocity, got " + velocity, velocity < 0f)

        val applied = wave.push(row, lag - 0.4f, row.top, tempo())
        assertTrue(applied.moving)
        assertTrue("the sub-pixel correction should have been merged", applied.merged)
        assertFalse(applied.velocityDropped)
        assertEquals(velocity, applied.keptVelocity, 1e-3f)
        // The running spring was not touched: same clock, same velocity, still one move.
        assertEquals(velocity, wave.velocityOf(row), 1e-3f)
        assertEquals(elapsed, wave.delayOf(row), 1e-3f)
        assertEquals(lag - 0.4f, row.translationY, 1e-3f)
        assertEquals(0, row.finished)

        // A correction above the floor is a real re-target and keeps its own contract.
        val retarget = wave.push(row, lag - 40f, row.top, tempo())
        assertFalse(retarget.merged)
        assertEquals(40f, retarget.layoutDeltaPx, 0.01f)
    }

    @Test
    fun `a correction that reverses a waiting row drops its stagger slot`() {
        // A row inside its slot has not moved yet. If the next layout puts the end point on the
        // other side of it, the queued slot belongs to a move that no longer exists: keeping it
        // would pin the row while the block visibly travels the other way.
        val first = Row("a", top = 200)
        val second = Row("b", top = 320)
        val wave = wave()
        wave.push(first, 300f, first.top, tempo())
        wave.push(second, 300f, second.top, tempo())
        wave.advance(16.6f)
        assertTrue("second should still be waiting", wave.delayOf(second) > 0f)

        val applied = wave.push(second, -120f, second.top, tempo())
        assertTrue(applied.moving)
        assertTrue(applied.waiting)
        assertTrue("the slot described a move that no longer exists", applied.delayDropped)
        assertEquals(0f, wave.delayOf(second), 0f)
        assertEquals(-120f, second.translationY, 1e-3f)

        wave.advance(16.6f)
        assertTrue("the row must leave now: " + second.translationY, second.translationY > -120f)
        wave.settle()
        assertEquals(0f, second.translationY, 0f)
        assertEquals(1, second.finished)
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

    // ---- the device's own timeline, replayed ------------------------------------------------

    /**
     * The host replayed with 1626's arithmetic.
     *
     * One line change is a layout move of [lineHeight] for every row, offered as one `animateMove`
     * per row whose `dy` already folds that row's current translation in
     * (`dy = toY - (fromY + translationY)`, `mc.y#m`), so `-dy` is the row's new lag. A *host-driven*
     * layout move ([layOut]) is the other half: Apple Music walking the block up by itself (a scroll,
     * a re-anchor, the animation the item animator's own move would have played), with no
     * `animateMove` to announce it.
     *
     * Every frame is checked the way the eye checks it: [snaps] collects "translationY was zeroed
     * while the layout had already moved on" (the visible jump) and [backwards] collects frames that
     * move a row back down.
     */
    private inner class Host(
        rowCount: Int = 6,
        private val lineHeight: Int = 120,
        paneHeightPx: Int = 1_000,
    ) {
        val rows: List<Row> = (0 until rowCount).map { Row("r" + it, 300 + it * lineHeight) }
        val wave: LyricLineSpringWave<Row> = wave(paneHeightPx)
        var nowMs = 0f
        var refused = 0
        val snaps = ArrayList<String>()
        val backwards = ArrayList<String>()
        private val lastVisual = HashMap<Row, Float>()
        private val lastTranslation = HashMap<Row, Float>()

        fun visual(row: Row): Float = row.top + row.translationY

        /** One line change: the block's layout moves up by [shift] and every row is offered its move. */
        fun change(shift: Int = lineHeight, touched: List<Row> = rows) {
            touched.forEach { row ->
                val fromY = row.top
                row.top -= shift
                val toY = row.top
                val dy = toY - (fromY + row.translationY.toInt())
                if (dy == 0) return@forEach
                if (!LyricLineSpringPolicy.acceptsMove(toY - fromY, -dy.toFloat())) {
                    refused += 1
                    wave.finishHolder(row, resetOffset = true)
                    return@forEach
                }
                wave.push(row, -dy.toFloat(), toY, tempo())
            }
        }

        /** The host moving the block's own layout, with no `animateMove` to announce it. */
        fun layOut(shift: Int, touched: List<Row> = rows) {
            touched.forEach { it.top += shift }
        }

        fun frame(dtMs: Float = 16.6f) {
            wave.advance(dtMs)
            nowMs += dtMs
            rows.forEach { row ->
                val now = visual(row)
                val previous = lastVisual[row]
                if (previous != null) {
                    if (now > previous + 1f) backwards += row.name + " +" + (now - previous)
                    val was = lastTranslation[row] ?: 0f
                    if (was != 0f && row.translationY == 0f && abs(now - previous) > 2f) {
                        snaps += row.name + " " + previous + "->" + now
                    }
                }
                lastVisual[row] = now
                lastTranslation[row] = row.translationY
            }
        }

        /** Frames until [untilMs] of song time, then every row must have moved since [since]. */
        fun segment(untilMs: Float, since: Map<Row, Float>, label: String) {
            var guard = 0
            while (nowMs < untilMs - 0.001f) {
                frame()
                guard += 1
                assertTrue(label + ": runaway", guard < 1_000)
            }
            assertMoved(since, label)
        }

        fun settle(label: String = "settle"): Int {
            var frames = 0
            while (wave.isRunning) {
                frame()
                frames += 1
                assertTrue(label + ": never settles", frames < 1_000)
            }
            return frames
        }

        /** Every row must have moved at least one pixel since [since] — a line change that reads. */
        fun assertMoved(since: Map<Row, Float>, label: String) {
            rows.forEach { row ->
                val moved = abs(visual(row) - since.getValue(row))
                assertTrue(
                    label + " " + row.name + " moved only " + moved + "px: the line change was swallowed",
                    moved >= 1f,
                )
            }
        }

        fun assertNoJump(label: String) {
            assertTrue(label + ": zeroed mid-travel: " + snaps, snaps.isEmpty())
            assertTrue(label + ": moved back down: " + backwards, backwards.isEmpty())
        }
    }

    @Test
    fun `a line change every three to eight hundred ms moves the block every time`() {
        listOf(300f, 500f, 800f).forEach { interval ->
            val host = Host()
            val label = "interval=" + interval + "ms"
            repeat(3) { index ->
                val before = host.rows.associateWith { host.visual(it) }
                host.change()
                host.segment(host.nowMs + interval, before, label + " change=" + index)
                host.assertNoJump(label + " change=" + index)
            }
            host.settle(label)
            host.rows.forEach {
                assertEquals(label + ": row left displaced", 0f, it.translationY, 0f)
            }
            assertEquals(label + ": handed a move back", 0, host.refused)
        }
    }

    @Test
    fun `change at zero, change at half a second, change at 1 point 2 s, then settle`() {
        // The device's own timeline for the reported bug: two line changes close enough together
        // that the second lands while the first spring is still travelling, then one more, then rest.
        val host = Host()
        val first = host.rows.associateWith { host.visual(it) }
        host.change() // t = 0
        host.segment(500f, first, "N")

        val second = host.rows.associateWith { host.visual(it) }
        host.change() // t = 0.5 s
        host.segment(1_200f, second, "N+1")

        val third = host.rows.associateWith { host.visual(it) }
        host.change() // t = 1.2 s
        host.settle("N+2")
        host.assertMoved(third, "N+2")
        host.assertNoJump("timeline")

        host.rows.forEach {
            assertEquals(0f, it.translationY, 0f)
            assertTrue("no finish was dispatched", it.finished >= 1)
        }
        assertEquals(0, host.refused)
        assertFalse(host.wave.isRunning)
    }

    @Test
    fun `the host walking the block up itself is followed, not cancelled`() {
        // 1626, 16:52:22: one takeover dy=-217, then ~290 px of layout movement over ~0.4 s with no
        // animateMove — the host's own animation of the line change. Folding those frames into the
        // residual pins the rows where the eye left them ("this line does not move"), and the
        // residual then runs to ±3600 where the takeover is handed back and the block snaps
        // ("and then it jumps to the line after next").
        val host = Host(rowCount = 12)
        host.change(shift = 217) // the log's takeover dy=-217
        val afterPush = host.rows.associateWith { it.translationY }
        val start = host.rows.associateWith { host.visual(it) }
        // Two of the twelve are off screen, so the layout leaves their tops alone; the ten that are
        // still visible all move by the same amount, which is exactly the case that used to leak.
        val visible = host.rows.take(10)
        listOf(-33, -102, -81, -64, -10).forEach { step ->
            host.layOut(step, visible)
            host.frame()
            host.rows.forEach { row ->
                assertTrue(
                    "residual grew to " + row.translationY + " (push wrote " + afterPush.getValue(row) + ")",
                    abs(row.translationY) <= abs(afterPush.getValue(row)) + 1f,
                )
            }
        }
        // Nothing was folded into a residual: the ten frames of host movement all happened on

        // screen, at the host's own rate.
        host.rows.forEach { row ->
            assertEquals("compensated the host's own movement", 0f, host.wave.compensatedOf(row), 0f)
        }
        // The block followed the layout instead of being held against it.
        visible.forEach { row ->
            assertTrue(
                "did not follow the host: " + row.name + " moved " + (start.getValue(row) - host.visual(row)),
                start.getValue(row) - host.visual(row) >= 250f,
            )
        }
        host.assertNoJump("host layout")
        assertEquals(0, host.refused)
    }

    @Test
    fun `a background line growing still rides under the rows it pushes down`() {
        // The case the compensation exists for: 4 px per frame, 12 frames, one row's line growing.
        val host = Host(rowCount = 4)
        host.change()
        val below = host.rows.drop(1)
        val top = host.rows.first()
        var previous = host.visual(below.first())
        repeat(12) {
            host.layOut(4, below)
            host.frame()
            val now = host.visual(below.first())
            // An upward line change: the row may keep travelling up while its layout grows down, but
            // it must never be pushed back down by the layout it is riding.
            assertTrue("moved back down with the background line: " + previous + " -> " + now, now <= previous + 1e-3f)
            previous = now
        }
        // Absorbed: the rows below stay put while their layout grows, and only the row above moves.
        assertTrue("absorbed nothing", host.wave.compensatedOf(below.first()) >= 40f)
        assertEquals(0f, host.wave.compensatedOf(top), 0f)
        host.settle()
        assertTrue(below.first().translationY <= 1f)
    }

    @Test
    fun `a line change that really moves is never reported as a skip`() {
        // The other half of the skip? diagnostic: a row that is travelling, staggered, or parked by
        // the interlude must not be reported as "this line change did nothing".
        val reported = ArrayList<String>()
        val rows = (0 until 4).map { Row("r" + it, top = 300 + it * 120) }
        val wave = LyricLineSpringWave<Row>(
            write = { row, offset -> row.translationY = offset },
            finish = { row -> row.finished += 1 },
            layoutTop = { row -> row.top },
            onSkipped = { holder, moved, lag, window ->
                reported += holder.toString() + " moved=" + moved + " lag=" + lag + " in " + window
            },
        ).also { it.paneHeightPx = 1_000 }
        wave.hold(400f) // the interlude's dots: parked, and not a swallowed move
        rows.forEach { wave.push(it, 240f, it.top, tempo()) }
        repeat(100) { wave.advance(16.6f) }
        assertTrue("a real line change was reported as a skip: " + reported, reported.isEmpty())
    }

    @Test
    fun `compensation stops at the cap so the residual cannot run away`() {
        // 6 px per frame for 30 frames = 180 px: one background line's worth is compensated, the rest
        // is followed (and logged as shift-cap), never folded into an unbounded residual.
        val refusals = ArrayList<String>()
        val rows = listOf(Row("only", top = 400))
        val wave = LyricLineSpringWave<Row>(
            write = { row, offset -> row.translationY = offset },
            finish = { row -> row.finished += 1 },
            layoutTop = { row -> row.top },
            onShiftSkipped = { _, delta, reason -> refusals += reason.name + ":" + delta },
        )
        wave.push(rows[0], 240f, rows[0].top, tempo())
        repeat(30) {
            rows[0].top += 6
            wave.advance(16.6f)
        }
        assertEquals(LyricLineSpringPolicy.MAX_COMPENSATION_PX.toFloat(), wave.compensatedOf(rows[0]), 8f)
        assertTrue("the cap never fired: " + refusals, refusals.any { it.startsWith("CAPPED") })
    }

    // ---- the layout-driven line change: no animateMove at all --------------------------------

    /** A wave that reports what it did with a bare layout jump, and the reasons it refused one. */
    private class Watched {
        val jumps = ArrayList<String>()
        val shifts = ArrayList<String>()
        val skips = ArrayList<String>()
        var listScrolling = false
        val wave = LyricLineSpringWave<Row>(
            write = { row, offset -> row.translationY = offset },
            finish = { row -> row.finished += 1 },
            layoutTop = { row -> row.top },
            onLayoutShift = { _, delta, lag -> shifts += delta.toString() + "/" + lag },
            onShiftSkipped = { _, delta, reason -> skips += reason.name + ":" + delta },
            onLayoutLineChange = { row, delta, applied ->
                row.started += 1
                jumps += row.name + " " + delta + " moving=" + applied.moving + " lag=" + applied.lagPx
            },
            canAbsorbJump = { !listScrolling },
        ).also { it.paneHeightPx = 1_000 }
    }

    @Test
    fun `a line change the host never announces still moves every row`() {
        // 1626 during playback: scrollToPositionWithOffset + requestLayout, no animateMove. The block
        // simply appears one line higher, so the only trace of it is each row's own top. Before this
        // the wave read that as "the block moved, follow it" and no row moved at all.
        val watched = Watched()
        val rows = (0 until 8).map { Row("r" + it, top = 300 + it * 120) }
        rows.forEach { watched.wave.track(it, it.top) }
        assertFalse("a seeded roster owes RecyclerView nothing", watched.wave.isRunning)
        assertTrue(watched.wave.isWatching)

        repeat(4) { change ->
            val before = rows.associateWith { it.visual() }
            val previous = before.toMutableMap()
            rows.forEach { it.top -= 300 }
            var frames = 0
            while (true) {
                watched.wave.advance(16.6f)
                frames += 1
                rows.forEach { row ->
                    val visual = row.visual()
                    assertTrue(
                        "change=" + change + " " + row.name + " went back down: " +
                            previous.getValue(row) + " -> " + visual,
                        visual <= previous.getValue(row) + 1e-2f,
                    )
                    previous[row] = visual
                }
                assertTrue("change=" + change + ": never settles", frames < 2_000)
                if (!watched.wave.isRunning) break
            }
            // Every row travelled the whole 300 px the layout jumped, and came to rest at home.
            rows.forEach { row ->
                assertEquals("change=" + change + ": left displaced", 0f, row.translationY, 1e-3f)
                val moved = before.getValue(row) - row.visual()
                assertTrue(
                    "change=" + change + " " + row.name + " moved " + moved + "px of 300",
                    moved >= 299f,
                )
            }
            // AMLL's settle time, not the host's: twenty to sixty frames at 60 Hz.
            assertTrue("change=" + change + " took " + frames + " frames", frames in 20..60)
        }
        // Eight rows, four line changes: every row starts and finishes once per change.
        assertEquals(32, watched.jumps.size)
        assertEquals(32, rows.sumOf { it.started })
        assertEquals(32, rows.sumOf { it.finished })
        assertTrue(
            "a resting row reported a background-line shift: " + watched.shifts,
            watched.shifts.isEmpty(),
        )
    }

    @Test
    fun `a seek is handed back instead of being replayed as a line change`() {
        val watched = Watched()
        val rows = (0 until 6).map { Row("r" + it, top = 300 + it * 120) }
        rows.forEach { watched.wave.track(it, it.top) }
        rows.forEach { it.top -= 2_460 } // the device log's own seek: -2460 px
        watched.wave.advance(16.6f)
        rows.forEach { assertEquals("a seek was absorbed", 0f, it.translationY, 0f) }
        assertFalse("a seek started a spring", watched.wave.isRunning)
        assertTrue("the seek was not reported: " + watched.skips, watched.skips.any { it == "SEEK:-2460" })
    }

    @Test
    fun `a one line jump under the user's finger is not taken over`() {
        // A drag moves the block exactly like a line change does. Only the host can tell them apart,
        // and it answers "the list is being scrolled".
        val watched = Watched()
        val rows = (0 until 6).map { Row("r" + it, top = 300 + it * 120) }
        rows.forEach { watched.wave.track(it, it.top) }
        watched.listScrolling = true
        rows.forEach { it.top -= 60 }
        watched.wave.advance(16.6f)
        rows.forEach { assertEquals("a scroll was absorbed", 0f, it.translationY, 0f) }
        assertTrue("the scroll was not reported: " + watched.skips, watched.skips.any { it == "SCROLL:-60" })
    }

    @Test
    fun `a tracked row that was never pushed still rides what the layout does`() {
        val watched = Watched()
        val row = Row("only", top = 400)
        watched.wave.track(row, row.top)
        row.top += 4 // the background line growing: no animateMove either
        watched.wave.advance(16.6f)
        assertEquals(-4f, row.translationY, 0.2f)
        var frames = 0
        while (watched.wave.advance(16.6f)) {
            frames += 1
            assertTrue("never settles", frames < 500)
        }
        assertEquals(0f, row.translationY, 0f)
        assertEquals("one shift, one finish", 1, row.finished)
        // A tracked row owes no start of its own: this one got none, and the shift is not a move.
        assertEquals(0, row.started)
    }

    @Test
    fun `a row whose view goes away stops being read`() {
        val row = Row("only", top = 400)
        var top: Int? = row.top
        val wave = LyricLineSpringWave<Row>(
            write = { holder, offset -> holder.translationY = offset },
            finish = { holder -> holder.finished += 1 },
            layoutTop = { top },
        )
        wave.track(row, row.top)
        assertTrue(wave.isWatching)
        top = null // recycled out of the list
        wave.advance(16.6f)
        assertFalse("a detached row was kept", wave.isWatching)
        assertEquals("a detached row was written", 0f, row.translationY, 0f)
    }
}
