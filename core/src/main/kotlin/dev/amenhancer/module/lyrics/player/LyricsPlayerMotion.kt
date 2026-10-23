package dev.amenhancer.module.lyrics.player

import dev.amenhancer.module.lyrics.motion.SpringTiming
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Motion model of the lyrics page: the four springs the iOS preset uses, the per-line
 * stagger, the scale/alpha hierarchy and the release-to-nearest-line target.
 *
 * Everything here is a pure function of time (or of a drag), so the unit tests can pin the
 * curve values and the renderer can sample once per frame without keeping animation objects.
 * [SpringTiming] is closed-form, which is why a spring state is just (from, target,
 * start time) — see [SpringChannel].
 */
object LyricsPlayerMotion {
    /** Line change / vertical scroll: ζ = 0.90, ω = 10 rad/s, settles in ~604 ms at 1 %. */
    val lineChange: SpringTiming = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 18.0)

    /** Whole-block lift used for drag release and large jumps: ζ ≈ 0.935, deliberately soft. */
    val lift: SpringTiming = SpringTiming(mass = 1.0, stiffness = 14.0, damping = 7.0)

    /** Touch-down emphasis: ζ ≈ 0.669. */
    val press: SpringTiming = SpringTiming(mass = 1.0, stiffness = 322.0, damping = 24.0)

    /** Touch release: slightly over-damped. */
    val release: SpringTiming = SpringTiming(mass = 2.0, stiffness = 300.0, damping = 50.0)

    const val DEFAULT_STAGGER_MS = 50
    const val MAX_STAGGER_LINES = 3
    const val DEFAULT_MOTION_PERCENT = 100
    const val MIN_MOTION_PERCENT = 40
    const val MAX_MOTION_PERCENT = 160

    /** Animation head start: the highlight runs 100 ms ahead of the reported position. */
    const val HEADSTART_MS = 100L

    /** How long a line keeps animating after it was fully sung. */
    const val FINISH_MS = 250L

    /** Progress freeze after tapping a line. */
    const val TAP_FREEZE_MS = 100L

    /** Auto-follow resumes this long after a drag is released. */
    const val DRAG_RESUME_DELAY_MS = 1200L

    const val FEATHER_DP = 30f
    const val GLOW_DP = 5f
    const val SYLLABLE_LIFT_DP = 2f

    const val DEFAULT_EMPHASIS_PERCENT = 114
    const val MIN_EMPHASIS_PERCENT = 100
    const val MAX_EMPHASIS_PERCENT = 114

    /** Resting scale of every line that is not the current one. */
    const val DESELECTED_SCALE = 0.98f

    /** Scale while a finger is down on a row. */
    const val PRESSED_SCALE = 0.95f

    /** Alpha/white level of a line that has already been sung. */
    const val PAST_WHITE = 0.40f

    /** Alpha/white level of a line that is still ahead. */
    const val FUTURE_WHITE = 0.55f

    /** Alpha/white level of the current (emphasised) line. */
    const val CURRENT_WHITE = 1.00f

    /**
     * Projection time used to turn a fling velocity into extra scroll distance. A quarter
     * of a second is short enough to stay on the intended line even on a hard flick
     * (the line-change spring itself is not used for distance, only for the settle).
     */
    const val FLING_PROJECTION_SECONDS = 0.25f

    /** Stagger of one line: 50 ms per line, at most three lines apart. */
    fun staggerDelayMs(
        lineIndex: Int,
        currentIndex: Int,
        staggerMs: Int = DEFAULT_STAGGER_MS,
    ): Long {
        val distance = abs(lineIndex - currentIndex).coerceAtMost(MAX_STAGGER_LINES)
        return staggerMs.coerceAtLeast(0).toLong() * distance
    }

    /** Duration multiplier of the motion setting; 100 % is Apple's own timing. */
    fun timeScale(motionPercent: Int = DEFAULT_MOTION_PERCENT): Float =
        motionPercent.coerceIn(MIN_MOTION_PERCENT, MAX_MOTION_PERCENT) / 100f

    /**
     * Emphasis of one line, 0 at rest and 1 when the line is fully the current one.
     *
     * [elapsedMs] is measured from the moment the current line changed. The line entering
     * and the line it replaced are staggered by [staggerDelayMs]; every other line is at
     * rest, so a jump of ten lines does not flash the nine lines in between.
     */
    fun lineEmphasis(
        elapsedMs: Long,
        lineIndex: Int,
        currentIndex: Int,
        previousIndex: Int,
        staggerMs: Int = DEFAULT_STAGGER_MS,
        motionPercent: Int = DEFAULT_MOTION_PERCENT,
    ): Float {
        val entering = lineIndex == currentIndex
        val leaving = !entering && lineIndex == previousIndex
        if (!entering && !leaving) return 0f
        val delay = staggerDelayMs(lineIndex, currentIndex, staggerMs)
        val seconds = (elapsedMs - delay).coerceAtLeast(0L) / 1000.0
        val progress = lineChange.value(seconds / timeScale(motionPercent)).coerceIn(0.0, 1.0)
        return (if (entering) progress else 1.0 - progress).toFloat()
    }

    /**
     * Scale of one line: unselected rows rest at 0.98, the current row grows from 1.0 to the
     * emphasis setting (114 %) as the line is sung, and [emphasis] blends between the two.
     */
    fun lineScale(
        emphasis: Float,
        lineProgress: Float,
        emphasisPercent: Int = DEFAULT_EMPHASIS_PERCENT,
    ): Float {
        val target = 1f + (emphasisPercent.coerceIn(MIN_EMPHASIS_PERCENT, MAX_EMPHASIS_PERCENT) / 100f - 1f) *
            lineProgress.coerceIn(0f, 1f)
        return DESELECTED_SCALE + (target - DESELECTED_SCALE) * emphasis.coerceIn(0f, 1f)
    }

    /** White level of a line: sung rows are dimmer than upcoming ones, the current row is white. */
    fun lineWhite(lineIndex: Int, currentIndex: Int): Float = when {
        lineIndex == currentIndex -> CURRENT_WHITE
        lineIndex < currentIndex -> PAST_WHITE
        else -> FUTURE_WHITE
    }

    /**
     * Line the scroll settles on after a drag. [velocityPxPerSecond] is positive when the
     * finger moved down (the content follows the finger, so the index decreases).
     */
    fun releaseTargetIndex(
        displayedIndex: Float,
        velocityPxPerSecond: Float,
        linePitchPx: Float,
        lineCount: Int,
    ): Int {
        if (lineCount <= 0) return 0
        if (linePitchPx <= 0f) return displayedIndex.roundToInt().coerceIn(0, lineCount - 1)
        val projectedLines = velocityPxPerSecond / linePitchPx * FLING_PROJECTION_SECONDS
        return (displayedIndex - projectedLines).roundToInt().coerceIn(0, lineCount - 1)
    }

    /** Soft lift spring for a big jump, the normal line-change spring otherwise. */
    fun springForJump(lineDistance: Float): SpringTiming =
        if (abs(lineDistance) > MAX_STAGGER_LINES) lift else lineChange
}

/**
 * One animated float sampled per frame from a [SpringTiming]. Deliberately mutable: the
 * frame loop keeps one instance per channel and never allocates while animating.
 */
class SpringChannel(var timing: SpringTiming = LyricsPlayerMotion.lineChange) {
    var value: Float = 0f
        private set

    var target: Float = 0f
        private set

    var isAnimating: Boolean = false
        private set

    private var startValue = 0f
    private var startNanos = 0L
    private var durationNanos = 0L
    private var timeScale = 1f

    /** Jump without animating. */
    fun snap(newValue: Float) {
        value = newValue
        target = newValue
        isAnimating = false
    }

    /** Start a spring from the current value to [newTarget]; a no-op when already there. */
    fun animateTo(newTarget: Float, nowNanos: Long, motionPercent: Int = LyricsPlayerMotion.DEFAULT_MOTION_PERCENT) {
        target = newTarget
        if (value == newTarget) {
            isAnimating = false
            return
        }
        startValue = value
        startNanos = nowNanos
        timeScale = LyricsPlayerMotion.timeScale(motionPercent)
        durationNanos = (timing.durationSeconds() * timeScale * 1_000_000_000.0).toLong().coerceAtLeast(1L)
        isAnimating = true
    }

    /** Sample the spring; the last frame snaps exactly onto the target. */
    fun valueAt(nowNanos: Long): Float {
        if (!isAnimating) return value
        val elapsedNanos = nowNanos - startNanos
        if (elapsedNanos >= durationNanos) {
            value = target
            isAnimating = false
            return value
        }
        val seconds = (elapsedNanos.coerceAtLeast(0L) / 1_000_000_000.0) / timeScale
        value = timing.value(seconds, from = startValue.toDouble(), to = target.toDouble()).toFloat()
        return value
    }
}
