package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeMiniDragPolicyTest {
    @Test
    fun capsulePressRemainsATapUntilUpwardMovementExceedsSlop() {
        val policy = TabletChromeMiniDragPolicy(12f)
        policy.start(1L, 100f, 900f, hitCapsule = true)
        assertTrue(policy.owns(1L))
        assertFalse(policy.move(1L, 101f, 895f))
        assertFalse(policy.move(1L, 100f, 888f))
        assertTrue(policy.move(1L, 100f, 887f))
    }

    @Test
    fun downwardAndHorizontalGesturesDoNotExpandTheCollapsedPlayer() {
        val policy = TabletChromeMiniDragPolicy(12f)
        policy.start(1L, 100f, 900f, hitCapsule = true)
        assertFalse(policy.move(1L, 100f, 950f))
        assertFalse(policy.move(1L, 160f, 880f))
        assertFalse(policy.move(1L, 140f, 860f))
    }

    @Test
    fun blankMarginDownCannotBecomeADragAfterMovingIntoTheCapsule() {
        val policy = TabletChromeMiniDragPolicy(12f)
        policy.start(1L, 10f, 900f, hitCapsule = false)
        assertFalse(policy.owns(1L))
        assertFalse(policy.move(1L, 300f, 600f))
        assertFalse(policy.move(1L, 10f, 300f))
    }

    @Test
    fun dragKeepsItsOwnerWhenTheFingerLeavesTheCapsuleOrReverses() {
        val policy = TabletChromeMiniDragPolicy(12f)
        policy.start(1L, 100f, 900f, hitCapsule = true)
        assertTrue(policy.move(1L, 100f, 880f))
        assertTrue(policy.move(1L, 100f, 400f))
        assertTrue(policy.move(1L, 500f, 700f))
        assertTrue(policy.move(1L, 100f, 920f))
        assertTrue(policy.dragging)
    }

    @Test
    fun newDownAndCancellationReleaseThePreviousGesture() {
        val policy = TabletChromeMiniDragPolicy(12f)
        policy.start(1L, 100f, 900f, hitCapsule = true)
        assertTrue(policy.move(1L, 100f, 850f))
        policy.clear()
        assertFalse(policy.owns(1L))
        assertFalse(policy.dragging)
        assertFalse(policy.move(1L, 100f, 100f))
        policy.start(2L, 100f, 900f, hitCapsule = true)
        assertFalse(policy.move(2L, 100f, 895f))
        assertTrue(policy.move(2L, 100f, 850f))
        policy.start(3L, 10f, 900f, hitCapsule = false)
        assertFalse(policy.dragging)
        assertFalse(policy.owns(2L))
        assertFalse(policy.move(3L, 10f, 600f))
    }

    @Test
    fun eventsFromAnotherGestureNeverAcquireTheNativeDrag() {
        val policy = TabletChromeMiniDragPolicy(12f)
        policy.start(1L, 100f, 900f, hitCapsule = true)
        assertFalse(policy.move(2L, 100f, 400f))
        assertFalse(policy.dragging)
        assertTrue(policy.move(1L, 100f, 400f))
        assertFalse(policy.move(2L, 100f, 300f))
    }

    @Test
    fun invalidCoordinatesAndSlopFailClosed() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val policy = TabletChromeMiniDragPolicy(12f)
            policy.start(1L, invalid, 900f, hitCapsule = true)
            assertFalse(policy.owns(1L))
            policy.start(2L, 100f, invalid, hitCapsule = true)
            assertFalse(policy.owns(2L))
            policy.start(3L, 100f, 900f, hitCapsule = true)
            assertFalse(policy.move(3L, invalid, 850f))
            assertFalse(policy.move(3L, 100f, invalid))
        }
        for (slop in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val policy = TabletChromeMiniDragPolicy(slop)
            policy.start(1L, 100f, 900f, hitCapsule = true)
            assertFalse(policy.move(1L, 100f, 850f))
        }
    }
}
