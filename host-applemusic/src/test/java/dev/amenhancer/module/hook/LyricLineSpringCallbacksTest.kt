package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one seam every replayed host callback goes through: a missing role, a wrong instance and a
 * throwing host method all answer `false` — one rate-limited log line — and never the animation.
 */
class LyricLineSpringCallbacksTest {
    class Animator {
        var calls = 0
        var lastHolder: Any? = null

        fun settle(holder: Any) {
            calls += 1
            lastHolder = holder
        }

        fun broken(holder: Any) {
            calls += 1
            throw IllegalStateException("the host's adapter is already gone")
        }
    }

    class OtherAnimator

    private val animator = Animator()
    private val holder = Any()

    @Test
    fun `a resolved role is invoked with its argument`() {
        val member = Animator::class.java.getDeclaredMethod("settle", Any::class.java)

        assertTrue(invokeHostCallback(member, animator, holder))
        assertEquals(1, animator.calls)
        assertEquals(holder, animator.lastHolder)
    }

    @Test
    fun `a role that did not resolve costs one false, not a crash`() {
        assertFalse(invokeHostCallback(null, animator, holder))
        assertEquals(0, animator.calls)
    }

    @Test
    fun `a throwing host method is swallowed`() {
        val member = Animator::class.java.getDeclaredMethod("broken", Any::class.java)

        // The host's own method ran (and threw); the spring reports the failure and keeps going.
        assertFalse(invokeHostCallback(member, animator, holder))
        assertEquals(1, animator.calls)
    }

    @Test
    fun `a member pinned on a subclass refuses another instance of the animator`() {
        val member = Animator::class.java.getDeclaredMethod("settle", Any::class.java)

        // Never invoked on a foreign instance: the call would throw on every row instead of once.
        assertFalse(invokeHostCallback(member, OtherAnimator(), holder))
        assertEquals(0, animator.calls)
    }
}
