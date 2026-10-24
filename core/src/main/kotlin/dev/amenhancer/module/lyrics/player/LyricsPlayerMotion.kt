package dev.amenhancer.module.lyrics.player

import dev.amenhancer.module.lyrics.motion.SpringTiming
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Motion model of the lyrics page: the four springs the iOS preset uses, the adaptive
 * per-line spring that the line gap selects, the scale/alpha hierarchy and the
 * release-to-nearest-line target.
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
     * Three durations, all with an evidence trail:
     *  - nominal (binary `duration()`): **0.918 s** = max(ln100/(ζω), 4/ω_d);
     *  - true unit-step envelope 1 % (incl. amplitude factor): **0.604 s**
     *    = ln(100/√(1−ζ²))/(ζω), the value [SpringTiming.durationSeconds] returns;
     *  - pure decay-term 1 %: **0.512 s** = ln(100)/(ζω), see [settleDurationSeconds].
     */
    val lineChange: SpringTiming = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 18.0)

    /**
     * Whole-block lift used for drag release and large jumps: ζ ≈ 0.935, response 1.678 s.
     * RE: A level (round 2) — .rodata 0x1d9000 = [1.0, 14.0], 0x1d7e30 = [7.0, 0.0];
     * used at fn 0x29c8878 (object +0x2e0..+0x2f8).
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

    /**
     * RE: A level (task-8 correction) — the value applyTransitionUpdate returns (the old
     * "lane") is discarded by its only caller (0x29cdbac -> 0x29cdbb0), so there is NO
     * lane/50 ms stagger in this build. Transitions are per line: every line owns its own
     * progress and spring (see gapSpring); the old stagger helpers were removed.
     */

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

    /**
     * Glow radius. RE: C level — 5.0 has no xref inside the lyrics code (round 2 negative
     * result); candidate 7.0 at Specs +0x80. Kept as a self-drawn appearance value.
     */
    const val GLOW_DP = 5f

    /** Syllable lift. RE: B level — Specs +0x88 = 2.0 (candidate syllableLift). */
    const val SYLLABLE_LIFT_DP = 2f

    /**
     * Current-line emphasis. RE: A level (round 2, negative) — 1.14 does not exist in the
     * binary; the selected line is 1.0 and deselected is 0.98. The default therefore does
     * *not* enlarge the current line; the 114 % upper bound stays only as a user preference
     * (settings slider), never as the default.
     */
    const val DEFAULT_EMPHASIS_PERCENT = 100
    const val MIN_EMPHASIS_PERCENT = 100
    const val MAX_EMPHASIS_PERCENT = 114

    // ---- adaptive per-line spring (RE: A parameters, A/B input meaning) ------------------
    //
    // The binary contains the "adaptive spring" the iOS author redacted: fn 0x29d30b4,
    // called from 0x29d2238 / 0x29d45dc, with the fallback branch bool=false equal to the
    // fixed 1.0/100.0/18.0 line spring. The public formula is
    //   p = clamp((x - 0.2) / 0.55, 0, 1)
    //   response = 0.48 + 0.27p   (0.48…0.75 s)
    //   zeta     = 0.90 - 0.12p   (0.90…0.78)
    // Round 3 found the dispatcher (0x29d2198): it picks adaptive / fixed 1.0/100/18 /
    // fixed 2.0/260/50 per line-render flag, so the original app itself falls back to the
    // fixed line spring; we keep that fixed spring everywhere.
    // x = gap between the current line's start and the previous line's end, in seconds.
    // RE: task-8 A level — the state machine (0x29f20ec) can feed that gap into the adaptive
    // formula, but the shipping build has 0x454 == 0 (strh wzr in the ctor): the fixed timing
    // path (the 0.89475907 s fallback) is what actually ships, and the adaptive branch only
    // runs if something sets that byte at runtime. The page therefore uses [lineChange] per
    // line by default; [gapSpring] is the optional adaptive mode.
    const val GAP_RESPONSE_MIN_S = 0.48f
    const val GAP_RESPONSE_MAX_S = 0.75f
    const val GAP_ZETA_MAX = 0.90f
    const val GAP_ZETA_MIN = 0.78f

    /**
     * Adaptive spring for one line's transition, selected by the gap before that line.
     * RE: A level parameters — p = clamp((gap - 0.2)/0.55, 0, 1), response 0.48 to 0.75 s,
     * zeta 0.90 to 0.78; longer gaps animate slower with slightly more bounce.
     */
    fun gapSpring(gapSeconds: Float): SpringTiming {
        val p = ((gapSeconds - GAP_X_MIN_S) / GAP_X_SPAN_S).coerceIn(0f, 1f)
        val response = GAP_RESPONSE_MIN_S + (GAP_RESPONSE_MAX_S - GAP_RESPONSE_MIN_S) * p
        val zeta = GAP_ZETA_MAX - (GAP_ZETA_MAX - GAP_ZETA_MIN) * p
        val omega = TWO_PI / response.toDouble()
        val stiffness = omega * omega
        val damping = 2.0 * zeta.toDouble() * omega
        return SpringTiming(mass = 1.0, stiffness = stiffness, damping = damping)
    }

    /**
     * The binary's fixed timing-path value (0x454 == 0 in the shipping build, set by the
     * ctor). The transition scheduler receives this instead of an adaptive response, i.e.
     * the default transition timing is fixed, not gap driven.
     */
    const val FALLBACK_TRANSITION_OFFSET_S = 0.89475907f

    private const val GAP_X_MIN_S = 0.2f
    private const val GAP_X_SPAN_S = 0.55f
    private const val TWO_PI = 2.0 * Math.PI

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

    /** Duration multiplier of the motion setting; 100 % is Apple's own timing. */
    fun timeScale(motionPercent: Int = DEFAULT_MOTION_PERCENT): Float =
        motionPercent.coerceIn(MIN_MOTION_PERCENT, MAX_MOTION_PERCENT) / 100f

    /**
     * Nominal spring duration, the Android binary's `TimingFunction::duration()` (0x28423c8).
     * Delegates to [SpringTiming.nominalDurationSeconds] so the model has a single owner:
     * `max(ln(100)/(ζω), 4/ω_d)` under-damped, `2m·ln(100)/c` otherwise. For [lineChange]
     * this is 0.918 s. RE: A level.
     */
    fun nominalDurationSeconds(timing: SpringTiming): Double = timing.nominalDurationSeconds()

    /**
     * Time for the exponential decay term alone to reach 1 %: `-ln(threshold)/(ζω)`, i.e.
     * 0.512 s for [lineChange]. It is the first term of the binary's `duration()`, *without*
     * the under-damped amplitude factor; the real unit-step envelope (0.604 s) also includes
     * that factor and is what [SpringTiming.durationSeconds] computes. Neither is the nominal
     * 0.918 s the binary actually schedules with — keep the three concepts apart.
     * RE: A level (epsilon 0.01 at spring state +0x58).
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
     * Scale of one line: unselected rows rest at 0.98, the current row grows from 1.0 to the
     * user's emphasis setting as the line is sung (the default setting is 100 %, i.e. no
     * enlargement — the binary's selected line is 1.0), and [emphasis] blends between the two.
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
