package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentArtworkRecoveryPolicyTest {
    @Test fun repeatedForegroundUsesTheNativeSlotWithoutCumulativeShrinkage() {
        var childWidth = 328
        var childHeight = 328
        repeat(30) {
            val slot = checkNotNull(FragmentArtworkRecoveryPolicy.nativeSquare(400, 400, true))
            assertEquals(FragmentArtworkRecoveryPolicy.SlotSize(400, 400), slot)
            assertEquals(it == 0, FragmentArtworkRecoveryPolicy.needsResize(childWidth, childHeight, slot))
            childWidth = slot.width
            childHeight = slot.height
        }
    }

    @Test fun pausedPlaybackScaleDoesNotBecomeTheBaseline() {
        val slot = checkNotNull(FragmentArtworkRecoveryPolicy.nativeSquare(400, 400, true))
        // Paused presentation can occupy 328px while absolute child layout remains 400px.
        assertEquals(328, (slot.width * .82f).toInt())
        assertFalse(FragmentArtworkRecoveryPolicy.needsResize(400, 400, slot))
        assertTrue(FragmentArtworkRecoveryPolicy.needsResize(328, 400, slot))
        assertEquals(400, slot.width)
    }

    @Test fun densityChangeUsesTheNewNativeGeometryAndSettlesAfterOneResize() {
        val before = checkNotNull(FragmentArtworkRecoveryPolicy.nativeSquare(320, 320, true))
        val after = checkNotNull(FragmentArtworkRecoveryPolicy.nativeSquare(480, 480, true))
        assertTrue(FragmentArtworkRecoveryPolicy.needsResize(before.width, before.height, after))
        assertFalse(FragmentArtworkRecoveryPolicy.needsResize(after.width, after.height, after))
        // A dual-pane artwork column can be smaller than either the window or the old slot.
        assertEquals(FragmentArtworkRecoveryPolicy.SlotSize(280, 280),
            FragmentArtworkRecoveryPolicy.nativeSquare(280, 280, true))
    }

    @Test fun animationAndVideoGatesRejectAnOtherwiseExpandedSheet() {
        assertTrue(allowed())
        assertFalse(allowed(entering = true))
        assertFalse(allowed(shared = true))
        assertFalse(allowed(animating = true))
        assertFalse(allowed(video = true))
        listOf(null, 1, 2, 4, 5, 6).forEach { assertFalse(allowed(state = it)) }
        assertFalse(allowed(shown = false))
        assertFalse(allowed(laidOut = false))
    }

    @Test fun onlyALaidOutNativeSquareWithPositiveDimensionsIsUsable() {
        assertEquals(FragmentArtworkRecoveryPolicy.SlotSize(400, 401),
            FragmentArtworkRecoveryPolicy.nativeSquare(400, 401, true))
        assertNull(FragmentArtworkRecoveryPolicy.nativeSquare(400, 402, true))
        assertNull(FragmentArtworkRecoveryPolicy.nativeSquare(400, 400, false))
        assertNull(FragmentArtworkRecoveryPolicy.nativeSquare(0, 0, true))
        assertNull(FragmentArtworkRecoveryPolicy.nativeSquare(1, 1, true))
        assertNull(FragmentArtworkRecoveryPolicy.nativeSquare(Int.MAX_VALUE, Int.MIN_VALUE, true))
    }

    @Test fun nativeLayoutSentinelsAreNotTreatedAsStaleAbsoluteChildSizes() {
        val slot = checkNotNull(FragmentArtworkRecoveryPolicy.nativeSquare(400, 400, true))
        assertFalse(FragmentArtworkRecoveryPolicy.needsResize(-1, -2, slot))
        assertFalse(FragmentArtworkRecoveryPolicy.needsResize(0, 0, slot))
        assertTrue(FragmentArtworkRecoveryPolicy.needsResize(-1, 328, slot))
    }

    private fun allowed(
        state: Int? = 3,
        entering: Boolean = false,
        shared: Boolean = false,
        animating: Boolean = false,
        video: Boolean = false,
        shown: Boolean = true,
        laidOut: Boolean = true,
    ): Boolean = FragmentArtworkRecoveryPolicy.canRecover(
        shown, laidOut, state, entering, shared, video, animating,
    )
}
