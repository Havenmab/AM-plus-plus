package dev.amenhancer.module.hook

import dev.amenhancer.glass.GlassCapsuleBounds
import dev.amenhancer.glass.GlassRepeatMode
import dev.amenhancer.glass.TopBarGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decisions for the iPad-style BOTTOM mini-player capsule. The geometry delegates to the
 * shared [TopBarGeometry] reference proportions, so these tests pin the delegation and the
 * centre/round-end invariants the touch gate and the mount rely on.
 */
class TabletChromeMiniPlayerPolicyTest {

    @Test
    fun `width and centring delegate to the shared top bar geometry`() {
        for (screen in listOf(0, 31, 32, 63, 64, 320, 1000, 1280, 2560)) {
            assertEquals(
                TopBarGeometry.miniPlayerWidthPx(screen),
                TabletChromeLayoutPolicy.miniPlayerWidthPx(screen),
            )
            assertEquals(
                TopBarGeometry.miniPlayerLeftPx(screen),
                TabletChromeLayoutPolicy.miniPlayerLeftPx(screen),
            )
        }
    }

    @Test
    fun `capsule stays centred with equal side margins`() {
        val screen = 1000
        val width = TabletChromeLayoutPolicy.miniPlayerWidthPx(screen)
        val left = TabletChromeLayoutPolicy.miniPlayerLeftPx(screen)
        assertEquals(250, left)
        assertEquals((screen - width) / 2, left)
        assertTrue("the capsule must fit inside the screen", left + width <= screen)
    }

    @Test
    fun `top margin keeps the capsule at the top of its host band`() {
        // The host's own peek height decides where that band sits; the policy adds no seat offset.
        assertEquals(0, TabletChromeLayoutPolicy.miniPlayerTopPx())
    }

    @Test
    fun `hit test follows the rendered capsule including its round ends`() {
        val bounds = GlassCapsuleBounds(left = 100f, top = 200f, right = 500f, bottom = 300f)
        assertTrue(TabletChromeLayoutPolicy.miniPlayerContains(300f, 250f, bounds))
        assertFalse(TabletChromeLayoutPolicy.miniPlayerContains(90f, 250f, bounds))
        assertFalse(TabletChromeLayoutPolicy.miniPlayerContains(510f, 250f, bounds))
        // The bounding box corner falls outside the capsule's round end.
        assertFalse(TabletChromeLayoutPolicy.miniPlayerContains(105f, 205f, bounds))
        // A degenerate capsule never reports a hit.
        assertFalse(
            TabletChromeLayoutPolicy.miniPlayerContains(
                0f,
                0f,
                GlassCapsuleBounds(0f, 0f, 0f, 0f),
            ),
        )
    }

    @Test
    fun `host repeat mode maps to the capsule three-state enum`() {
        assertEquals(GlassRepeatMode.OFF, TabletChromeLayoutPolicy.repeatModeOf(0))
        assertEquals(GlassRepeatMode.ONE, TabletChromeLayoutPolicy.repeatModeOf(1))
        assertEquals(GlassRepeatMode.ALL, TabletChromeLayoutPolicy.repeatModeOf(2))
        // Unknown host values must not throw in the render path.
        assertEquals(GlassRepeatMode.OFF, TabletChromeLayoutPolicy.repeatModeOf(-1))
        assertEquals(GlassRepeatMode.OFF, TabletChromeLayoutPolicy.repeatModeOf(99))
    }
}
