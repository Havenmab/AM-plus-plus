package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping half of 「换行弹簧」: what a row is told to do, and how many times.
 *
 * RecyclerView's pending-move ledger takes exactly one "this animation finished" per started
 * move, so the tests below count finished callbacks against a fake holder rather than asserting
 * "the flag is set somewhere".
 */
class LyricLineSpringBatchTest {
    /** Stands in for a RecyclerView.ViewHolder + its itemView's translationY. */
    private class Row(val name: String) {
        var translationY = 0f
        var finished = 0
    }

    private fun spring(durationMs: Long = 600L, dy: Float = -300f): LyricLineSpring =
        requireNotNull(LyricLineSpringPolicy.springFor(durationMs, dy))

    private fun batch(
        dy: Float = -300f,
        durationMs: Long = 600L,
        rows: List<Row>,
    ): LyricLineSpringBatch<Row> = LyricLineSpringBatch<Row>(
        spring = spring(durationMs, dy),
        write = { row, offset -> row.translationY = offset },
        finish = { row -> row.finished += 1 },
    ).also { created -> rows.forEach { created.join(it) } }

    @Test
    fun `a shared batch writes the same offset to every row`() {
        val rows = listOf(Row("a"), Row("b"), Row("c"))
        val batch = batch(rows = rows)

        assertEquals(3, batch.size)
        assertTrue(batch.isRunning)
        rows.forEach { assertEquals(-300f, it.translationY, 1e-3f) }

        batch.advance(16.6f)
        val first = rows.first().translationY
        // Identical to the last bit: a torn block is the failure mode this rules out.
        rows.forEach { assertEquals(first, it.translationY, 0f) }
        // And moving: Apple Music's own displacement is 300 px away from rest.
        assertTrue(first > -300f)
        assertTrue(first < 0f)
    }

    @Test
    fun `the batch snaps to zero and finishes every row exactly once`() {
        val rows = listOf(Row("a"), Row("b"))
        val batch = batch(rows = rows)

        var frames = 0
        while (batch.advance(16.6f)) {
            frames += 1
            assertTrue("never settles", frames < 10_000)
        }
        // ~1.3 s at 16.6 ms, i.e. the spring's own tempo, not an infinite loop.
        assertTrue("frames=$frames", frames in 40..140)
        rows.forEach {
            assertEquals(0f, it.translationY, 0f)
            assertEquals(1, it.finished)
        }
        assertFalse(batch.isRunning)

        // Further frames (a stale callback) must not emit a second finished.
        batch.advance(16.6f)
        batch.finishAll()
        rows.forEach { assertEquals(1, it.finished) }
    }

    @Test
    fun `endAnimation zeroes the row once and never rewrites it afterwards`() {
        val rows = listOf(Row("a"), Row("b"))
        val batch = batch(rows = rows)
        batch.advance(16.6f)

        assertTrue(batch.finishHolder(rows[0]))
        assertEquals(0f, rows[0].translationY, 0f)
        assertEquals(1, rows[0].finished)
        // The other row keeps moving; the ended one is no longer written.
        val before = rows[1].translationY
        batch.advance(16.6f)
        assertEquals(0f, rows[0].translationY, 0f)
        assertTrue(rows[1].translationY > before)

        // Calling it again (the host may cancel twice) is a no-op, not a second finished.
        assertFalse(batch.finishHolder(rows[0]))
        assertEquals(1, rows[0].finished)
        assertFalse(batch.contains(Row("zzz")))
    }

    /**
     * A re-target must not flash the old position: the row's layout already jumped, so the ended
     * animation is finished without zeroing, and the new batch writes the new offset in the same
     * host call.
     */
    @Test
    fun `a re-target finishes the old move without zeroing the row`() {
        val row = Row("a")
        val first = batch(dy = -300f, rows = listOf(row))
        first.advance(100f)
        val carried = first.currentVelocity()
        val shownBefore = row.translationY
        assertTrue(shownBefore < 0f)

        assertTrue(first.finishHolder(row, resetOffset = false))
        assertEquals(1, row.finished)
        assertEquals(shownBefore, row.translationY, 0f)

        val second = batch(dy = -120f, rows = listOf(row))
        assertEquals(-120f, row.translationY, 1e-3f)
        assertTrue(carried > 0f)

        var frames = 0
        while (second.advance(16.6f)) frames += 1
        assertTrue("frames=$frames", frames in 10..140)
        assertEquals(0f, row.translationY, 0f)
        assertEquals(2, row.finished)
    }

    @Test
    fun `a long frame step is clamped so a stall cannot teleport the block`() {
        val stalled = Row("stalled")
        val stepped = Row("stepped")
        val stalledBatch = batch(rows = listOf(stalled))
        val steppedBatch = batch(rows = listOf(stepped))

        stalledBatch.advance(5_000f)
        steppedBatch.advance(LyricLineSpringPolicy.MAX_FRAME_STEP_MS)

        assertEquals(stepped.translationY, stalled.translationY, 0f)
        assertEquals(LyricLineSpringPolicy.MAX_FRAME_STEP_MS, stalledBatch.elapsed, 0f)
    }

    @Test
    fun `joining twice never costs a finished and pruning keeps the batch bounded`() {
        val row = Row("a")
        val batch = batch(rows = listOf(row))
        batch.join(row)
        batch.advance(16.6f)
        assertEquals(1, batch.size)

        batch.finishHolder(row)
        batch.prune()
        assertEquals(0, batch.size)
        assertFalse(batch.isRunning)
        assertEquals(1, row.finished)
    }
}
