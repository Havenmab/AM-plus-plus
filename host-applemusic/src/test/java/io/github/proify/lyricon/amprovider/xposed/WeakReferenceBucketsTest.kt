package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakReferenceBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bucket registration ported from HLE v8.2.1 (`1a665f69`).
 *
 * Static measure: registering the same binding 100 times in the old per-key
 * queue scanned the queue on every call (0+1+…+99 ≈ 5 000 identity checks);
 * [WeakReferenceBuckets.add] performs one bucket pass per registration and
 * de-duplicates there, and a bounded sweep drops dead entries.
 */
class WeakReferenceBucketsTest {

    @Test
    fun `the same identity is registered once per key`() {
        val buckets = WeakReferenceBuckets<String, Any>()
        val binding = Any()

        assertTrue(buckets.add("42", binding))
        assertFalse(buckets.add("42", binding))
        assertFalse(buckets.add("42", binding))

        assertEquals(1, buckets["42"]?.size)
        assertEquals(1, buckets.keyCount)
    }

    @Test
    fun `distinct identities accumulate in one key`() {
        val buckets = WeakReferenceBuckets<String, Any>()
        buckets.add("42", Any())
        buckets.add("42", Any())
        buckets.add("43", Any())

        assertEquals(2, buckets["42"]?.size)
        assertEquals(1, buckets["43"]?.size)
        assertEquals(2, buckets.keyCount)
    }

    @Test
    fun `sweep and clear keep the registry consistent`() {
        val buckets = WeakReferenceBuckets<String, Any>()
        buckets.add("alive", Any())
        buckets.add("alive", Any())

        // A sweep must never drop a live reference (the JVM cannot be forced to
        // collect deterministically, so only the live half is asserted).
        buckets.sweep()
        assertNotNull(buckets["alive"])
        assertEquals(2, buckets["alive"]!!.size)

        buckets.clear()
        assertEquals(0, buckets.keyCount)
        assertTrue(buckets["alive"] == null)
    }
}
