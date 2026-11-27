package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「间奏退场」 x 「换行弹簧」: the rows must wait for the dots, then leave in AMLL's order.
 *
 * The host's own order is *dots first*: when an interlude ends, the three dots grow, shrink and fade
 * (`V0#N` / `A#N`, 750 ms + 250 ms) and only then is the line change perceptible. So while that
 * animation runs a row must (a) keep the position the layout jump left it at — writing
 * `translationY` is what makes the jump invisible — and (b) not even start counting its 50 ms stair.
 */
class LyricLineSpringHoldTest {
    private class Row(val name: String, var top: Int) {
        var translationY = 0f
        var finished = 0
        override fun toString(): String = name + "@" + top
    }

    private fun tempo(): LyricLineSpringTempo = LyricLineSpringPolicy.tempo

    private fun wave(paneHeightPx: Int = 0): LyricLineSpringWave<Row> =
        LyricLineSpringWave<Row>(
            write = { row, offset -> row.translationY = offset },
            finish = { row -> row.finished += 1 },
            layoutTop = { row -> row.top },
        ).also { it.paneHeightPx = paneHeightPx }

    /** Frames of 16.6 ms; returns how many were simulated. */
    private fun LyricLineSpringWave<Row>.run(frames: Int) {
        repeat(frames) { advance(16.6f) }
    }

    /** Frames until every row has come to rest. */
    private fun LyricLineSpringWave<Row>.settle(limit: Int = 4_000): Int {
        var frames = 0
        while (advance(16.6f)) {
            frames += 1
            assertTrue("never settles", frames < limit)
        }
        return frames
    }

    @Test
    fun aHeldBatchWritesItsStartPositionAndThenDoesNotMoveAtAll() {
        val rows = (0 until 3).map { Row("r" + it, top = 400 + it * 120) }
        val wave = wave(paneHeightPx = 1_000)
        wave.hold(LyricInterludeExitPolicy.EXIT_MS.toFloat())
        rows.forEach { wave.push(it, -240f, it.top, tempo()) }

        // The layout jump is already compensated: the row is drawn where the eye left it.
        rows.forEach { assertEquals(-240f, it.translationY, 1e-3f) }
        // 60 frames = ~1 s of exit animation: nothing moves, and the wave keeps answering "running"
        // so RecyclerView does not conclude the list is idle and drop the pending moves.
        wave.run(60)
        rows.forEach { assertEquals(-240f, it.translationY, 1e-3f) }
        assertTrue(wave.isRunning)
        rows.forEach { assertEquals(0, it.finished) }
    }

    @Test
    fun theStaggerOnlyStartsWhenTheExitIsOver() {
        val rows = (0 until 4).map { Row("r" + it, top = 300 + it * 120) }
        val wave = wave(paneHeightPx = 2_000)
        wave.hold(LyricInterludeExitPolicy.EXIT_MS.toFloat())
        rows.forEach { wave.push(it, -240f, it.top, tempo()) }

        // While the hold runs the stagger is frozen: the row that sorts first has not gained a
        // single frame of head start over the last one.
        wave.run(59)
        rows.forEach { assertEquals(-240f, it.translationY, 1e-3f) }
        assertEquals(wave.delayOf(rows[0]), wave.delayOf(rows[1]) - 50f, 1f)

        // Frames 61..63 cross the 1000 ms deadline and step the first row's spring; the rows below
        // it are still inside their own 50 ms slots, which is AMLL's order resuming after the dots.
        wave.run(3)
        assertTrue("the top row should be leaving", rows[0].translationY > -240f)
        assertEquals(-240f, rows[3].translationY, 0f)
    }

    @Test
    fun aRowAlreadyTravellingFreezesWhereItIsInsteadOfJumping() {
        // The exit's start signal arrives from a host animation callback, i.e. a frame or two after
        // the push. Whatever the row has already travelled must not snap back.
        val row = Row("r0", top = 400)
        val wave = wave()
        wave.push(row, -240f, row.top, tempo())
        wave.advance(16.6f)
        val travelled = row.translationY
        assertTrue("the row should have started: " + travelled, travelled > -240f)

        wave.hold(500f)
        wave.run(20)
        assertEquals(travelled, row.translationY, 1e-3f) // frozen, not zeroed, not re-aimed
        wave.run(20) // past the hold
        assertTrue(row.translationY > travelled)
    }

    @Test
    fun theEndCallbackReleasesTheHoldEarly() {
        val rows = (0 until 2).map { Row("r" + it, top = 400 + it * 120) }
        val wave = wave()
        wave.hold(1_000f)
        rows.forEach { wave.push(it, -240f, it.top, tempo()) }
        wave.run(10)
        rows.forEach { assertEquals(-240f, it.translationY, 1e-3f) }

        // The exit finished after 166 ms: the rows may go now, with their stagger intact.
        wave.release()
        wave.run(3)
        assertTrue(rows[0].translationY > -240f)
        assertTrue(wave.isRunning)
    }

    @Test
    fun aRowPushedWhileTheExitIsRunningJoinsTheSameWait() {
        // A later layout pass (the background line's height animation, a second changed row) must
        // not slip through the hold: pendingHoldMs covers every push of that window.
        val wave = wave()
        val first = Row("r0", top = 400)
        wave.hold(800f)
        wave.push(first, -240f, first.top, tempo())
        val late = Row("r1", top = 520)
        wave.run(5)
        wave.push(late, -240f, late.top, tempo())
        assertEquals(-240f, late.translationY, 1e-3f)
        wave.run(20)
        assertEquals(-240f, late.translationY, 1e-3f)
        assertEquals(-240f, first.translationY, 1e-3f)
        wave.run(40)
        assertTrue(first.translationY > -240f)
    }

    @Test
    fun aSecondLineChangeInsideTheExitIsAimCorrectionNotAnEarlierStart() {
        val row = Row("r0", top = 400)
        val wave = wave()
        wave.hold(1_000f)
        wave.push(row, -240f, row.top, tempo())
        wave.run(10)
        // The host re-anchors again (a seek, a second notify) while the dots are still leaving.
        val applied = wave.push(row, -260f, row.top, tempo())
        assertTrue(applied.moving)
        assertEquals(-260f, row.translationY, 1e-3f)
        // Still parked: the corrected end point does not buy the row an earlier start.
        wave.run(30)
        assertEquals(-260f, row.translationY, 1e-3f)
        // The correction re-aimed the move instead of retiring it: nothing is owed while the row is
        // still parked, and exactly one finish is owed when it finally ends.
        assertEquals(0, row.finished)
        wave.release()
        wave.settle()
        assertEquals(1, row.finished)
    }

    @Test
    fun aHoldOfZeroChangesNothing() {
        val row = Row("r0", top = 400)
        val wave = wave()
        wave.hold(0f)
        wave.push(row, -240f, row.top, tempo())
        wave.advance(16.6f)
        assertTrue(row.translationY > -240f)
        assertFalse(wave.isRunning && row.translationY == -240f)
    }
}
