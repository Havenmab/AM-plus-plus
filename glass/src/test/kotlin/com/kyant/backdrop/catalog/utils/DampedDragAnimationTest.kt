package com.kyant.backdrop.catalog.utils

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class DampedDragAnimationTest {
    @Test fun heldDragFollowsTargetsUpdatedEveryFrame() {
        assertHeldDragFollowsTargets(frames = 60, movesPerFrame = 1)
    }

    @Test fun heldDragFollowsTheLatestOfSeveralMovesPerFrame() {
        assertHeldDragFollowsTargets(frames = 45, movesPerFrame = 3)
    }

    private fun assertHeldDragFollowsTargets(frames: Int, movesPerFrame: Int) {
        val harness = Harness()
        try {
            harness.animation.press()
            repeat(frames) { frame ->
                repeat(movesPerFrame) { move ->
                    val step = frame * movesPerFrame + move + 1
                    harness.animation.updateValue(4f * step / (frames * movesPerFrame))
                }
                harness.frame()
            }
            // Assert before lifting the finger or giving the final target extra frames.
            assertTrue("Held drag must follow the finger; value=${harness.animation.value}",
                harness.animation.value > 3.5f)
            assertEquals(4f, harness.animation.targetValue, .001f)
            assertEquals(1f, harness.animation.pressProgress, .001f)
            assertEquals(1.45f, harness.animation.scaleX, .001f)
            assertEquals(1.45f, harness.animation.scaleY, .001f)
        } finally { harness.close() }
    }

    @Test fun queuedDragFrameCannotRestoreVelocityAfterTabSettles() {
        val harness = Harness()
        try {
            harness.startDrag()
            assertTrue("The drag must produce velocity before settling", harness.animation.velocity > 0f)

            // Queue tab settling before resuming a drag frame. The old frame can
            // otherwise enqueue a velocity write after the settling reset.
            harness.animation.animateToValue(4f)
            harness.sendFrame(800)
            harness.drain()
            harness.animation.release()
            harness.finish()

            assertEquals(4f, harness.animation.value, .001f)
            assertEquals(0f, harness.animation.velocity, .001f)
            assertEquals(0f, harness.animation.pressProgress, .001f)
            assertEquals(1f, harness.animation.scaleX, .001f)
            assertEquals(1f, harness.animation.scaleY, .001f)
        } finally { harness.close() }
    }

    @Test fun newPressCancelsThePreviousReleaseWhileMotionIsPending() {
        val harness = Harness()
        try {
            harness.startDrag()
            harness.animation.animateToValue(4f)
            harness.animation.release()
            harness.frame()

            // The next finger goes down while the prior tab is still settling.
            harness.animation.press()
            harness.finish()

            assertEquals(1f, harness.animation.pressProgress, .001f)
            assertEquals(1.45f, harness.animation.scaleX, .001f)
            assertEquals(1.45f, harness.animation.scaleY, .001f)
        } finally { harness.close() }
    }

    private class Harness {
        private val dispatcher = QueuedDispatcher()
        private val clock = BroadcastFrameClock()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher + clock +
            CoroutineExceptionHandler { _, error -> errors += error })
        private var timeNanos = 0L
        val animation = DampedDragAnimation(scope, initialValue = 0f, valueRange = 0f..4f,
            visibilityThreshold = .001f, initialScale = 1f, pressedScale = 1.45f,
            pressedScaleY = 1.45f, onDragStarted = {}, onDragStopped = {}, onDrag = { _, _ -> })

        fun startDrag() {
            animation.press()
            animation.updateValue(3.4f)
            repeat(6) {
                // The production velocity tracker samples wall-clock timestamps.
                Thread.sleep(5)
                frame()
            }
        }

        fun sendFrame(milliseconds: Long = 16) {
            timeNanos += milliseconds * 1_000_000
            clock.sendFrame(timeNanos)
        }

        fun drain() {
            dispatcher.drain()
            Snapshot.sendApplyNotifications()
            dispatcher.drain()
            if (errors.isNotEmpty()) throw AssertionError("Animation coroutine failed", errors.first())
        }

        fun frame() { drain(); sendFrame(); drain() }
        fun finish() { repeat(180) { frame() } }
        fun close() { scope.cancel(); dispatcher.drain() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
