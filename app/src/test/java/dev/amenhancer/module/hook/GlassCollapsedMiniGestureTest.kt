package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassCollapsedMiniGestureTest {
    @Test fun `a press on the collapsed mini is owned`() {
        val gesture = GlassCollapsedMiniGesture()
        assertFalse(gesture.owning)
        gesture.down(100f, 200f)
        assertTrue(gesture.owning)
    }

    @Test fun `ownership lasts until the gesture ends`() {
        val gesture = GlassCollapsedMiniGesture()
        gesture.down(100f, 200f)
        // Movement is deliberately NOT classified any more. Whether the finger became a drag, and
        // which control it landed on, is the host's business: the module only needs to know that
        // this gesture is the mini's so the collapse tracker stands down for all of it.
        assertTrue(gesture.owning)
        gesture.reset()
        assertFalse(gesture.owning)
    }

    @Test fun `a reset without a down owns nothing`() {
        val gesture = GlassCollapsedMiniGesture()
        gesture.reset()
        assertFalse(gesture.owning)
    }

    @Test fun `degenerate coordinates never claim the gesture`() {
        // A non-finite coordinate cannot be located on screen, so the collapse tracker must stay
        // live rather than being silenced by a gesture that never really happened.
        GlassCollapsedMiniGesture().let {
            it.down(Float.NaN, 200f)
            assertFalse(it.owning)
        }
        GlassCollapsedMiniGesture().let {
            it.down(100f, Float.NaN)
            assertFalse(it.owning)
        }
    }
}
