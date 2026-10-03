package dev.amenhancer.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarGeometryTest {
    @Test
    fun `consumed top space adds inset gap and host capsule height`() {
        // 0 inset + 8dp * 2 density + the host's 112px navigation_tabs_height.
        assertEquals(128, TopBarGeometry.occupiedTopHeight(112, 0, 2f))
        // The default gap is TOP_GAP_DP, and an explicit override changes only the gap term.
        assertEquals(128, TopBarGeometry.occupiedTopHeight(112, 0, 2f, TopBarGeometry.TOP_GAP_DP))
        assertEquals(144, TopBarGeometry.occupiedTopHeight(112, 0, 2f, gapDp = 16))
        assertEquals(112, TopBarGeometry.occupiedTopHeight(112, 0, 2f, gapDp = 0))
        // The status-bar inset is added verbatim, in pixels, on top of the scaled gap.
        assertEquals(152, TopBarGeometry.occupiedTopHeight(112, 24, 2f))
    }

    @Test
    fun `gap is scaled by density and truncated like the bottom policy`() {
        assertEquals(68, TopBarGeometry.occupiedTopHeight(56, 0, 1.5f))
        assertEquals(110, TopBarGeometry.occupiedTopHeight(100, 0, 1.3f)) // 10.4 -> 10
        assertEquals(21, TopBarGeometry.occupiedTopHeight(0, 0, 2.625f)) // 21.0
    }

    @Test
    fun `capsule corner is half the host capsule height`() {
        assertEquals(28f, TopBarGeometry.capsuleCornerPx(56), 0f)
        assertEquals(21.5f, TopBarGeometry.capsuleCornerPx(43), 0f)
        assertEquals(0f, TopBarGeometry.capsuleCornerPx(0), 0f)
    }

    @Test
    fun `mini capsule is about half the screen and keeps its side margins`() {
        assertEquals(640, TopBarGeometry.miniPlayerWidthPx(1280))
        assertEquals(500, TopBarGeometry.miniPlayerWidthPx(1000))
        // The margin clamp binds exactly where half the screen would eat both margins.
        assertEquals(32, TopBarGeometry.miniPlayerWidthPx(64))
        assertEquals(
            64 - 2 * TopBarGeometry.MIN_SIDE_MARGIN_PX,
            TopBarGeometry.miniPlayerWidthPx(64),
        )
    }

    @Test
    fun `mini capsule is centred`() {
        val screen = 1000
        val width = TopBarGeometry.miniPlayerWidthPx(screen)
        val left = TopBarGeometry.miniPlayerLeftPx(screen)
        assertEquals(250, left)
        assertEquals((screen - width) / 2, left)
        // Both margins are equal; an odd remainder leaves the extra pixel on the right.
        val remainder = screen - width - 2 * left
        assertTrue("margins must differ by at most one pixel", remainder == 0 || remainder == 1)
    }

    @Test
    fun `mini capsule invariants hold across widths`() {
        for (screen in 0..3000) {
            val width = TopBarGeometry.miniPlayerWidthPx(screen)
            val left = TopBarGeometry.miniPlayerLeftPx(screen)
            assertTrue("width $width must be non-negative for $screen", width >= 0)
            assertTrue("width $width must not exceed screen $screen", width <= screen)
            assertTrue("left $left must be non-negative for $screen", left >= 0)
            assertTrue("left + width must fit in $screen", left + width <= screen)
            assertEquals("$screen must be centred", (screen - width) / 2, left)
            if (screen >= 2 * TopBarGeometry.MIN_SIDE_MARGIN_PX) {
                assertTrue(
                    "width $width must keep both side margins on $screen",
                    width <= screen - 2 * TopBarGeometry.MIN_SIDE_MARGIN_PX,
                )
            } else {
                assertEquals("no room for margins on $screen", 0, width)
            }
            // Narrow widths must be stable rather than oscillate between calls.
            assertEquals(width, TopBarGeometry.miniPlayerWidthPx(screen))
        }
    }

    @Test
    fun `mini capsule never grows again as the screen narrows`() {
        var previous = 0
        for (screen in 32..2000) {
            val width = TopBarGeometry.miniPlayerWidthPx(screen)
            assertTrue("width must not shrink as the screen grows ($screen)", width >= previous)
            previous = width
        }
    }
}
