package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the identity semantics of [WeakIdentityMap] after the HLE v8.2.1
 * (`1a665f69`) probe rewrite: `get`/`remove` match by identity (not `equals`),
 * and `putIfAbsent` reports the first registration.
 *
 * The rewrite replaced a freshly allocated weak reference on every lookup with a
 * reusable identity probe. This test keeps the observable contract honest; the
 * allocation itself is visible in the method shape (no `IdentityWeakReference`
 * is constructed on a get/remove path), not measurable from JUnit.
 */
class WeakIdentityMapIdentityTest {

    @Test
    fun `equal but distinct objects remain distinct keys`() {
        val map = WeakIdentityMap<EqualKey, String>()
        val first = EqualKey("same")
        val second = EqualKey("same")

        map[first] = "first"

        assertEquals("first", map[first])
        assertNull(map[second])
        assertTrue(first !== second)
    }

    @Test
    fun `putIfAbsent registers only once and reports it`() {
        val map = WeakIdentityMap<Any, Unit>()
        val key = Any()

        assertTrue(map.putIfAbsent(key, Unit))
        assertFalse(map.putIfAbsent(key, Unit))
        assertEquals(listOf(key), map.keys())
    }

    @Test
    fun `remove matches by identity and clears the entry`() {
        val map = WeakIdentityMap<EqualKey, Int>()
        val key = EqualKey("k")
        map[key] = 1

        map.remove(EqualKey("k"))
        assertEquals(1, map[key])

        map.remove(key)
        assertNull(map[key])
    }

    private class EqualKey(private val value: String) {
        override fun equals(other: Any?): Boolean = other is EqualKey && other.value == value
        override fun hashCode(): Int = value.hashCode()
    }
}
