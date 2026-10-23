package dev.amenhancer.module.lyrics.player

import dev.amenhancer.module.lyrics.motion.SpringTiming
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    /**
     * Line change / vertical scroll: ζ = 0.90, ω = 10 rad/s.
     * RE: A level — inline in `LyricsRenderer` ctor (this+0x4e0..0x4f0).
     * Nominal duration is 0.918 s (see [nominalDurationSeconds]); 1 % settle is 0.512 s.
     */
    val lineChange: SpringTiming = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 18.0)

    /**
     * Whole-block lift used for drag release and large jumps: ζ ≈ 0.935, deliberately soft.
     * RE: C level — this exact spring is not in the Android binary; 14.0 only appears as a
     * Specs field (+0xE4). The A-level second spring is [secondSpring] (2.0/260.0/50.0).
     */
    val lift: SpringTiming = SpringTiming(mass = 1.0, stiffness = 14.0, damping = 7.0)

    /**
     * The second spring inline in the renderer (mass 2.0 / stiffness 260.0 / damping 50.0,
     * ζ = 1.095). RE: A level — this is the Android highlight/touch-release spring.
     */
    val secondSpring: SpringTiming = SpringTiming(mass = 2.0, stiffness = 260.0, damping = 50.0)

    /**
     * Touch-down emphasis: mass 1.0 / stiffness 322.2728 / damping 35.9039, ζ = 1.0.
     * RE: A level — Android binary `SpringTiming` ctor 0x284047c. Unlike iOS (ζ = 0.669)
     * this one is critically damped, so the press has no overshoot.
     */
    val press: SpringTiming = SpringTiming(mass = 1.0, stiffness = 322.2728, damping = 35.9039)

    /**
     * Touch release: mass 2.0 / stiffness 260.0 / damping 50.0, ζ = 1.095.
     * RE: A level — the renderer's second inline spring (this+0x580), not iOS' 2/300/50.
     */
    val release: SpringTiming = secondSpring

    const val DEFAULT_STAGGER_MS = 50

    /**
     * RE: A level — the transition state machine (0x29f20ec) hands the incoming lines to
     * 5 lanes (0..4), `lane = 4 - g`. `lineDelay` (0.05 s) is the lane step, not a per-line
     * linear delay. We approximate the lane of a line by its distance from the current one.
     */
    const val STAGGER_LANES = 5

    /** Highest lane index, i.e. the longest stagger (4 × step). */
    const val MAX_STAGGER_LANE = STAGGER_LANES - 1

    /** A jump farther than this many lines uses the soft lift spring. */
    const val LIFT_JUMP_LINES = 3
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

    /**
     * Karaoke fill feather. RE: A level — Specs +0x54 = 40.0 (the iOS notes said 30, the
     * Android build and the iOS build value are both 40).
     */
    const val FEATHER_DP = 40f

    /** Glow radius. RE: C level — 5.0 not found in the binary; candidate 7.0 at +0x80. */
    const val GLOW_DP = 5f

    /** Syllable lift. RE: B level — Specs +0x88 = 2.0 (candidate syllableLift). */
    const val SYLLABLE_LIFT_DP = 2f

    /** Current-line emphasis upper bound. RE: C level — 1.14 not found in the binary yet. */
    const val DEFAULT_EMPHASIS_PERCENT = 114
    const val MIN_EMPHASIS_PERCENT = 100
    const val MAX_EMPHASIS_PERCENT = 114

    /** Resting scale of every line that is not the current one. RE: A level — Specs +0x40 = 0.98. */
    const val DESELECTED_SCALE = 0.98f

    /** Scale while a finger is down on a row. RE: A level — Specs +0x5c = 0.95. */
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

    /**
     * Transition lane of [lineIndex] when [currentIndex] becomes current: 0 (starts first)
     * to [MAX_STAGGER_LANE] (starts last). RE: A level model, C level mapping (we use the
     * line distance instead of the binary's group index `g`).
     */
    fun staggerLane(lineIndex: Int, currentIndex: Int): Int =
        abs(lineIndex - currentIndex).coerceAtMost(MAX_STAGGER_LANE)

    /** Stagger of one line: lane × step, where the settings slider scales the step. */
    fun staggerDelayMs(
        lineIndex: Int,
        currentIndex: Int,
        staggerMs: Int = DEFAULT_STAGGER_MS,
    ): Long = staggerLane(lineIndex, currentIndex).toLong() * staggerMs.coerceAtLeast(0).toLong()

    /** Duration multiplier of the motion setting; 100 % is Apple's own timing. */
    fun timeScale(motionPercent: Int = DEFAULT_MOTION_PERCENT): Float =
        motionPercent.coerceIn(MIN_MOTION_PERCENT, MAX_MOTION_PERCENT) / 100f

    /**
     * Nominal spring duration, the Android binary's `TimingFunction::duration()`
     * (0x28423c8): `max(ln(100)/(ζω), 4/ω_d)` while under-damped and `2m·ln(100)/c`
     * (i.e. `ln(100)/(ζω)`) once critical/over-damped. For [lineChange] this is 0.918 s.
     * RE: A level.
     */
    fun nominalDurationSeconds(timing: SpringTiming): Double {
        val zeta = timing.dampingRatio
        val omega = timing.naturalFrequency
        val decay = LN_100 / (zeta * omega)
        if (zeta < 1.0 - CRITICAL_EPSILON) {
            val dampedOmega = omega * sqrt(1.0 - zeta * zeta)
            return maxOf(decay, 4.0 / dampedOmega)
        }
        return decay
    }

    /**
     * Time until the spring is within [threshold] of its target, i.e. the binary's
     * `isSettled` epsilon (0.01). For [lineChange] this is 0.512 s — distinct from the
     * nominal 0.918 s above, and the reason 604 ms must not be used for either concept.
     * RE: A level (epsilon 0.01 @spring state +0x58).
     */
    fun settleDurationSeconds(timing: SpringTiming, threshold: Double = 0.01): Double {
        val zeta = timing.dampingRatio
        val omega = timing.naturalFrequency
        if (zeta < 1.0 - CRITICAL_EPSILON) {
            return -ln(threshold) / (zeta * omega)
        }
        // The critical/over-damped curve carries a (1 + ωt) factor, so its 1 % point is past
        // the decay constant; the binary only ever needs the nominal duration for those.
        return nominalDurationSeconds(timing)
    }

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
        if (abs(lineDistance) > LIFT_JUMP_LINES) lift else lineChange

    private const val LN_100: Double = 4.605170185988091

    /** Below this a spring counts as under-damped (same epsilon the binary's setup uses). */
    private const val CRITICAL_EPSILON: Double = 1e-4
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
        durationNanos = (
            LyricsPlayerMotion.nominalDurationSeconds(timing) * timeScale * 1_000_000_000.0
            ).toLong().coerceAtLeast(1L)
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
