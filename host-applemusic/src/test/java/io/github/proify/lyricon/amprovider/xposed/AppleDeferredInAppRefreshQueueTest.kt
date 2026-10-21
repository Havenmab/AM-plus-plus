package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the coalescing contract ported from HLE v8.2.1 (`1a665f69`): repeated
 * applications of one id in a message produce exactly one pending refresh, a
 * force request wins, a synchronous refresh cancels a pending one, and a
 * budgeted drain's leftovers run before newly offered ids.
 *
 * Static measure this replaces: a batch applying the same id 4 times used to call
 * the in-app surface refresh 4 times; the queue drains 1 entry.
 */
class AppleDeferredInAppRefreshQueueTest {

    @Test
    fun `four applications of one id coalesce into one pending refresh`() {
        val queue = AppleDeferredInAppRefreshQueue()

        assertTrue(queue.offer("42", forceRebind = false))
        assertFalse(queue.offer("42", forceRebind = false))
        assertFalse(queue.offer("42", forceRebind = false))
        assertFalse(queue.offer("42", forceRebind = false))

        val drained = queue.drain()
        assertEquals(4, drained.offers)
        assertEquals(mapOf("42" to false), drained.pending)
        assertEquals(0, queue.size())
    }

    @Test
    fun `a force request wins over a plain one for the same id`() {
        val queue = AppleDeferredInAppRefreshQueue()
        queue.offer("7", forceRebind = true)
        queue.offer("7", forceRebind = false)

        assertEquals(mapOf("7" to true), queue.drain().pending)
    }

    @Test
    fun `a synchronous refresh cancels a pending entry`() {
        val queue = AppleDeferredInAppRefreshQueue()
        queue.offer("9", forceRebind = false)

        queue.cancel("9")

        assertTrue(queue.drain().pending.isEmpty())
    }

    @Test
    fun `remaining entries are restored ahead of newly offered ids`() {
        val queue = AppleDeferredInAppRefreshQueue()
        queue.offer("1", forceRebind = false)
        queue.offer("2", forceRebind = false)
        queue.drain()

        // A budgeted drain put the unreached id back, then the next message offered one.
        assertTrue(queue.restore(mapOf("2" to false)))
        queue.offer("3", forceRebind = true)

        val next = queue.drain()
        assertEquals(listOf("2", "3"), next.pending.keys.toList())
        assertEquals(false, next.pending["2"])
        assertEquals(true, next.pending["3"])
        // Restored entries are not new offers.
        assertEquals(1, next.offers)
    }

    @Test
    fun `restoring nothing requests no extra message`() {
        val queue = AppleDeferredInAppRefreshQueue()
        assertFalse(queue.restore(emptyMap()))
    }
}
