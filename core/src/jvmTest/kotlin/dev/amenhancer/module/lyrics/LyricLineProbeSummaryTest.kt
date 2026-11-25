package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineProbeSummaryTest {
    private fun summary(): LyricLineProbeSummary = LyricLineProbeSummary()

    @Test
    fun `an empty window reports zeros and no conclusions`() {
        val snapshot = summary().snapshot()

        assertEquals(0, snapshot.changes)
        assertEquals(0, snapshot.moves)
        assertEquals(0f, snapshot.movesPerChange, 0.0001f)
        // No move at all is not evidence that moves are zero-displacement.
        assertFalse(snapshot.zeroDelta)
        assertFalse(snapshot.instrumentalOnly)
        assertNull(snapshot.flushMeanMs)
        assertNull(snapshot.flushMaxMs)
    }

    @Test
    fun `the sentinel line change never enters moves per change`() {
        val summary = summary()
        summary.lineChange(sentinel = true)
        summary.lineChange(sentinel = true)
        summary.lineChange(sentinel = false)
        summary.move(zeroDelta = true)
        summary.move(zeroDelta = true)

        val snapshot = summary.snapshot()

        assertEquals(1, snapshot.changes)
        assertEquals(2, snapshot.sentinelChanges)
        // Two moves for one real change is Apple's own outgoing + incoming row pair.
        assertEquals(2f, snapshot.movesPerChange, 0.0001f)
    }

    @Test
    fun `zero displacement holds only while every move is zero displacement`() {
        val summary = summary()
        summary.lineChange(sentinel = false)
        summary.move(zeroDelta = true)
        assertTrue(summary.snapshot().zeroDelta)

        summary.move(zeroDelta = false)
        val snapshot = summary.snapshot()
        assertFalse(snapshot.zeroDelta)
        assertEquals(2, snapshot.moves)
        assertEquals(1, snapshot.zeroDeltaMoves)
    }

    @Test
    fun `row transitions tell instrumental rows from text rows`() {
        val summary = summary()
        summary.rowTransition(instrumental = true)
        summary.rowTransition(instrumental = true)
        assertTrue(summary.snapshot().instrumentalOnly)

        summary.rowTransition(instrumental = false)
        val snapshot = summary.snapshot()
        assertEquals(3, snapshot.rowTransitions)
        assertEquals(1, snapshot.textRowTransitions)
        assertFalse(snapshot.instrumentalOnly)
    }

    @Test
    fun `flush delays average and keep their maximum`() {
        val summary = summary()
        summary.flush(400L)
        summary.flush(500L)
        summary.flush(450L)

        val snapshot = summary.snapshot()

        assertEquals(3, snapshot.flushSamples)
        assertEquals(450L, snapshot.flushMeanMs)
        assertEquals(500L, snapshot.flushMaxMs)
    }

    @Test
    fun `a negative delay is not a sample`() {
        val summary = summary()
        summary.flush(-1L)

        assertNull(summary.snapshot().flushMeanMs)
        assertEquals(0, summary.snapshot().flushSamples)
    }

    @Test
    fun `reset starts a fresh window`() {
        val summary = summary()
        summary.lineChange(sentinel = false)
        summary.lineChange(sentinel = true)
        summary.move(zeroDelta = true)
        summary.rowTransition(instrumental = false)
        summary.flush(320L)

        summary.reset()
        val snapshot = summary.snapshot()

        assertEquals(0, snapshot.changes)
        assertEquals(0, snapshot.sentinelChanges)
        assertEquals(0, snapshot.moves)
        assertEquals(0, snapshot.rowTransitions)
        assertEquals(0, snapshot.textRowTransitions)
        assertEquals(0, snapshot.flushSamples)
        assertNull(snapshot.flushMeanMs)
        assertNull(snapshot.flushMaxMs)
    }

    @Test
    fun `a long window keeps averaging instead of overflowing`() {
        val summary = summary()
        repeat(1_000) {
            summary.lineChange(sentinel = false)
            summary.move(zeroDelta = true)
            summary.move(zeroDelta = true)
        }

        val snapshot = summary.snapshot()

        assertEquals(1_000, snapshot.changes)
        assertEquals(2_000, snapshot.moves)
        assertEquals(2f, snapshot.movesPerChange, 0.0001f)
        assertTrue(snapshot.zeroDelta)
    }
}
