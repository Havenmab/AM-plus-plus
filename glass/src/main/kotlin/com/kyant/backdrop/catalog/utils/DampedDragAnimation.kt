/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * Changed by AM++: tap-only native reselection, independent pressed Y scale,
 * and cancellation of obsolete motion/press jobs with complete release cleanup.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.catalog.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    // AM++: observe a completed tap on the floating indicator for native tab reselection.
    val onTap: (() -> Unit)? = null,
    val pressedScaleY: Float = pressedScale,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private var motionJob: Job? = null
    private var velocityJob: Job? = null
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private var velocityGeneration = 0

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        var distance = 0f
        inspectDragGestures(
            onDragStart = { down ->
                distance = 0f
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                if (distance <= viewConfiguration.touchSlop) onTap?.invoke()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            distance += dragAmount.getDistance()
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        val generation = ++velocityGeneration
        velocityJob?.cancel()
        velocityTracker.resetTracking()
        velocityJob = animationScope.launch {
            if (generation == velocityGeneration) resetVelocity()
        }
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScaleY, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { abs(valueAnimation.value - valueAnimation.targetValue) }
                    .first { it < threshold }
            }
            // Invalidate queued drag-frame writes before returning to the idle shape.
            velocityGeneration++
            velocityJob?.cancelAndJoin()
            pressJob?.cancelAndJoin()
            coroutineScope {
                launch { resetVelocity() }
                launch {
                    pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
                    pressProgressAnimation.snapTo(0f)
                }
                launch {
                    scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec)
                    scaleXAnimation.snapTo(initialScale)
                }
                launch {
                    scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec)
                    scaleYAnimation.snapTo(initialScale)
                }
            }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        val generation = velocityGeneration
        motionJob = animationScope.launch {
            // Retarget the running spring through Animatable's own cancellation.
            // Eager cancellation on every move discards its frame time and velocity.
            if (generation == velocityGeneration) {
                valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity(generation) }
            }
        }
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        beginSettling()
        press()
        motionJob = animationScope.launch {
            coroutineScope {
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                // Always reset, even when a pending velocity write has not started yet.
                launch { resetVelocity() }
            }
        }
        release()
    }

    private fun beginSettling() {
        velocityGeneration++
        motionJob?.cancel()
        velocityJob?.cancel()
    }

    private suspend fun resetVelocity() {
        velocityAnimation.animateTo(0f, velocityAnimationSpec)
        velocityAnimation.snapTo(0f)
    }

    private fun updateVelocity(generation: Int) {
        if (generation != velocityGeneration) return
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        velocityJob = animationScope.launch {
            if (generation == velocityGeneration) {
                velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec)
            }
        }
    }
}
