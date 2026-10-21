package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the main-thread budget ported from HLE v8.2.1 (`1a665f69`): a queue of
 * callbacks runs in order, one message spends about [AppleBudgetedResultQueue.DEFAULT_BUDGET_NS]
 * and the rest continues in a later message.
 *
 * Static measure: three 4 ms callbacks used to run in one message (12 ms); with
 * a 6 ms budget the same work is split over two messages, never more than two
 * callbacks per message.
 */
class AppleBudgetedResultQueueTest {

    @Test
    fun `tasks run in submission order across budgeted messages`() {
        val clock = FakeClock()
        val messages = ArrayDeque<Runnable>()
        val queue = AppleBudgetedResultQueue(
            post = messages::addLast,
            clock = clock::nanoTime,
            budgetNs = 6_000_000L,
        )
        val ran = mutableListOf<Int>()
        repeat(5) { index ->
            queue.enqueue {
                ran += index
                clock.advanceMs(4)
            }
        }

        var messagesRun = 0
        while (messages.isNotEmpty()) {
            messages.removeFirst().run()
            messagesRun += 1
        }

        assertEquals(listOf(0, 1, 2, 3, 4), ran)
        // 4 ms per task, 6 ms budget: two per message, then one → three messages.
        assertEquals(3, messagesRun)
    }

    @Test
    fun `a message always runs at least one task even over budget`() {
        val clock = FakeClock()
        val messages = ArrayDeque<Runnable>()
        val queue = AppleBudgetedResultQueue(
            post = messages::addLast,
            clock = clock::nanoTime,
            budgetNs = 1L,
        )
        var ran = 0
        queue.enqueue {
            ran += 1
            clock.advanceMs(50)
        }

        messages.removeFirst().run()

        assertEquals(1, ran)
        assertEquals(0, messages.size)
    }

    private class FakeClock {
        private var now = 0L
        fun nanoTime(): Long = now
        fun advanceMs(millis: Long) {
            now += millis * 1_000_000L
        }
    }
}
