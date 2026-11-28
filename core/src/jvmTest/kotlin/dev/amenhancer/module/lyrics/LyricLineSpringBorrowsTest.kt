package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「isRunning 借了要还」: what the spring owes the host for answering "still animating".
 *
 * The host's own "animations finished" callback is only ever dispatched by the animation-end path a
 * takeover skips, so the ledger is the whole rule — and the rule is exactly-once *per animator per
 * epoch*, not per wave. The tests below are the four shapes that rule can be wrong in: a wave that
 * ends mid-flight, the last wave, an animator we never borrowed from, and the next epoch.
 */
class LyricLineSpringBorrowsTest {
    private class Animator(val name: String) {
        override fun toString(): String = name
    }

    @Test
    fun `the last wave of an animator hands back exactly one answer`() {
        val animator = Animator("lyrics")
        val ledger = LyricLineSpringBorrows()
        ledger.waveStarted(animator)
        ledger.waveStarted(animator)
        ledger.borrow(animator)

        // One of the two waves ended: the animator is still animating, so nothing is handed back.
        assertNull(ledger.waveEnded(animator))
        assertEquals(1, ledger.owed(animator))

        // The last one ended: one answer, and the animator stops claiming to be busy.
        assertEquals(1, ledger.waveEnded(animator))
        assertEquals(0, ledger.owed(animator))
        // Exactly once: a second end (or a stray one) hands back nothing.
        assertNull(ledger.waveEnded(animator))
    }

    @Test
    fun `a wave that ends mid flight hands nothing back`() {
        val animator = Animator("lyrics")
        val ledger = LyricLineSpringBorrows()
        repeat(3) { ledger.waveStarted(animator) }
        repeat(40) { ledger.borrow(animator) } // 40 frames of answering "still running"

        assertNull(ledger.waveEnded(animator))
        assertNull(ledger.waveEnded(animator))
        assertEquals(40, ledger.owed(animator))

        // The last wave is the one that closes the epoch, and it carries the whole count.
        assertEquals(40, ledger.waveEnded(animator))
        assertEquals(0, ledger.owed(animator))
    }

    @Test
    fun `an animator we never borrowed from is never handed back`() {
        val animator = Animator("lyrics")
        val ledger = LyricLineSpringBorrows()

        // isRunning() was never asked while this wave was live (a very short line, or a list that
        // never queried it): there is no listener queued on our answer, so nothing is owed.
        ledger.waveStarted(animator)
        assertNull(ledger.waveEnded(animator))
        assertEquals(0, ledger.owed(animator))
    }

    @Test
    fun `the next wave borrows again and is handed back again`() {
        val animator = Animator("lyrics")
        val ledger = LyricLineSpringBorrows()

        ledger.waveStarted(animator)
        ledger.borrow(animator)
        ledger.borrow(animator)
        assertEquals(2, ledger.waveEnded(animator))

        // A later line change: a fresh epoch, its own answers, its own hand-back.
        ledger.waveStarted(animator)
        ledger.borrow(animator)
        assertEquals(1, ledger.owed(animator))
        assertEquals(1, ledger.waveEnded(animator))
        assertEquals(0, ledger.owed(animator))
    }

    @Test
    fun `two panes own two independent debts`() {
        val left = Animator("left")
        val right = Animator("right")
        val ledger = LyricLineSpringBorrows()
        ledger.waveStarted(left)
        ledger.waveStarted(right)
        ledger.borrow(left)
        ledger.borrow(right)
        ledger.borrow(right)

        // Ending one pane's wave must not spend the other pane's epoch.
        assertEquals(1, ledger.waveEnded(left))
        assertEquals(2, ledger.owed(right))
        assertEquals(2, ledger.waveEnded(right))
    }

    @Test
    fun `forget drops what an animator owes`() {
        val animator = Animator("lyrics")
        val ledger = LyricLineSpringBorrows()
        ledger.waveStarted(animator)
        ledger.borrow(animator)

        ledger.forget(animator)
        assertEquals(0, ledger.owed(animator))
        assertNull(ledger.waveEnded(animator))
    }
}
