package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.ModuleSettings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The spring that replaces Apple Music's own lyric line-move displacement.
 *
 * Apple Music 7.0.3 moves the whole lyric block in one go: its item animator (`mc.y#m`) sets
 * `translationY = -dy` on every visible row of the batch and hands those rows to a
 * `ViewPropertyAnimator` that runs for `$k.e` milliseconds. This policy describes the *same* kind
 * of displacement as one scalar spring per row, so the host hook only has to write one number per
 * frame per row:
 *
 * ```
 * m  = 0.9
 * t  = 0.450 s                   // AMLL's settle time; fixed, no host input
 * k  = 170 * (0.484 / t)^2       // = 196.7
 * c  = 2.2 * sqrt(k)             // = 2 zeta sqrt(k m), zeta = 1.1595 (just past critical) = 30.9
 * ```
 *
 * Those numbers are AMLL's (`line-transition-notes/03-amll-spec-v1.md` §2.1): 170 is AMLL's
 * "normal" stiffness and 0.484 s is the t99 AMLL pairs with it; 0.450 s is the fast end of AMLL's
 * own k = 170..220 normal mode. Apple Music's own move duration (`$k.e`) is deliberately **not**
 * read: a line change takes AMLL's time here, which is the whole point of the feature.
 *
 * **The curve is AMLL's, not the ODE's.** For `zeta > 1` the exact solution of
 * `x'' = (-k x - c x') / m` is a pair of exponentials whose slow root decays at
 * `omega0 (zeta - sqrt(zeta^2 - 1))`; a critically damped spring instead follows
 * `(x0 + t(v0 + omega0 x0)) e^(-omega0 t)`. AMLL's `spring.ts` uses the second form for *every*
 * `zeta >= 1` (`soft || 1 <= damping / (2 sqrt(k m))`), so at the same stiffness its block arrives
 * 1.31x sooner at the 1 % gate — the "t99 = 1.31 T" 乙-1 measured against the host's own field.
 * [LyricLineSpringDecay] carries that choice: production always uses [LyricLineSpringDecay.AMLL],
 * and the exact solution stays available as the reference curve the unit tests validate against an
 * independent RK4 integration.
 *
 * Deliberately pure: no Android, no reflection, no clock. [LyricLineSpringWave] owns the frame
 * stepping and the exactly-once "finished" bookkeeping, so both are testable on the JVM.
 */
object LyricLineSpringPolicy {
    /** AMLL's moving mass. */
    const val MASS: Float = 0.9f

    /** AMLL's normal damping ratio: just past critical, so there is no visible overshoot. */
    const val DAMPING_RATIO: Float = 1.1595f

    /** AMLL's "normal" stiffness, paired with [REFERENCE_SETTLE_MS]. */
    const val REFERENCE_STIFFNESS: Float = 170f

    /** AMLL's t99 for [REFERENCE_STIFFNESS]; the constant that turns a settle time into a tempo. */
    const val REFERENCE_SETTLE_MS: Float = 484f

    /** `2 * 1.1595 * sqrt(0.9)`, pre-multiplied: `c = DAMPING_COEFFICIENT * sqrt(k)`. */
    const val DAMPING_COEFFICIENT: Float = 2.2f

    /**
     * The settle time every line change is replayed at, in milliseconds.
     *
     * `k = 170 (0.484 / 0.45)^2 = 196.7`, the fast end of AMLL's own k = 170..220 normal mode.
     * Fixed on purpose: the feature exists to give line changes AMLL's time rather than the time
     * Apple Music computed for the line, so no host value feeds it.
     */
    const val AMLL_SETTLE_MS: Float = 450f

    /**
     * Beyond this the layout jump is a seek, not a line change: the spring would visibly fly across
     * the screen, so Apple Music's instant re-anchor is the correct look and the move stays with the
     * host.
     *
     * This bounds the *layout jump* of one move (`toY - fromY`), not the residual the row carries.
     * The two are the same number only in a slow song: the host folds the row's current translation
     * into `dy`, so a fast song accumulates a whole line change per lyric line into the residual
     * while every individual layout jump is still one line — see [acceptsMove].
     */
    const val MAX_DISPLACEMENT_PX: Int = 1_200

    /**
     * Beyond this one row is chasing so much accumulated residual that it is no longer a line
     * change. Three takeover widths is several screens: a jump, a list replacement or a re-anchor
     * storm, where chasing is worse than the host's own re-anchor.
     */
    const val MAX_RESIDUAL_PX: Float = 3f * MAX_DISPLACEMENT_PX

    /**
     * The smallest net displacement worth re-aiming a spring at, in pixels.
     *
     * Two line changes whose `dy` cancels out (the background line growing and then collapsing
     * inside one batch, a re-anchor that lands where the row already is) leave a sub-pixel
     * correction. Re-launching the spring for that would cost the row its velocity and its clock
     * for nothing, so such a push is merged into the running spring instead and only logged.
     */
    const val MIN_VISIBLE_DELTA_PX: Float = 1f

    /**
     * The frame step is clamped so that a stalled main thread (GC, a disk read, a fling) cannot
     * teleport a row by half a screen in a single frame.
     */
    const val MAX_FRAME_STEP_MS: Float = 100f

    /** Sub-pixel: below one physical pixel of any shipped density, so the final snap is invisible. */
    const val SETTLE_EPSILON_PX: Float = 0.5f

    /** The frame rate [SETTLE_EPSILON_VELOCITY] is derived from; a reference, not a truth. */
    const val REFERENCE_FRAME_SECONDS: Float = 1f / 60f

    /**
     * A row counts as arrived only when it is both sub-pixel *and* slower than one sub-pixel per
     * reference frame. The position gate alone is enough for a decaying spring, but a line change
     * that lands while the row is still moving can leave |x| small and |v| large; snapping there
     * would stop the row dead in the middle of its travel.
     */
    const val SETTLE_EPSILON_VELOCITY: Float = SETTLE_EPSILON_PX / REFERENCE_FRAME_SECONDS

    /**
     * The largest per-frame layout shift that is still "a background line grew".
     *
     * A background line growing from nothing moves the rows below it by its own height over a few
     * hundred milliseconds, i.e. **4-6 px per frame** measured on 1626. Anything faster is not a
     * line growing but the host moving the block itself — its own line-change animation, a scroll,
     * a re-anchor, a recycled row — and folding that into the residual does not make it invisible,
     * it *pins the row to the screen* while the layout runs away underneath: the residual grows by
     * every frame the host animates, the block stops moving ("this line change does nothing"), and
     * past [MAX_RESIDUAL_PX] the takeover is handed back and everything snaps at once ("and then it
     * jumps straight to the line after next").
     *
     * 1626 measured 33/102/81/64 px on four consecutive frames of a 290 px host animation, i.e. an
     * order of magnitude above a growing line; 8 px sits between the two and leaves the measured
     * 4-6 px case a 60 % margin.
     */
    const val MAX_COMPENSABLE_SHIFT_PX: Int = 8

    /**
     * How much one line change may take out of a row's travel in total, in pixels.
     *
     * The per-frame bound alone still lets a *slow* host movement (6-8 px per frame, e.g. a smooth
     * scroll to the new anchor) be compensated frame after frame, which is the same "glued to the
     * screen" failure at a lower rate. One background line is tens of pixels and a whole line change
     * is ~120 px, so 96 px is "a background line grew, with room to spare" and can never hold a row
     * still for longer than that.
     */
    const val MAX_COMPENSATION_PX: Int = 96

    /** The one spring every line change is replayed at; see [AMLL_SETTLE_MS]. */
    val tempo: LyricLineSpringTempo = LyricLineSpringTempo(
        tempoMs = AMLL_SETTLE_MS,
        stiffness = stiffnessFor(AMLL_SETTLE_MS),
        damping = dampingFor(stiffnessFor(AMLL_SETTLE_MS)),
        decay = LyricLineSpringDecay.AMLL,
    )

    /**
     * True when a per-frame layout shift of [deltaPx] is a background line growing.
     *
     * Anything larger is the host moving the block itself (see [MAX_COMPENSABLE_SHIFT_PX]) and must
     * be *followed*, not cancelled: the row stays where the layout puts it and its spring simply
     * keeps aiming at 0.
     */
    fun acceptsLayoutShift(deltaPx: Int): Boolean =
        deltaPx != 0 && abs(deltaPx) <= MAX_COMPENSABLE_SHIFT_PX

    /** True when [deltaPx] still fits under [MAX_COMPENSATION_PX] after [compensatedPx] was taken. */
    fun acceptsCompensation(compensatedPx: Float, deltaPx: Int): Boolean =
        abs(compensatedPx) + abs(deltaPx) <= MAX_COMPENSATION_PX

    /** True when [dy] is a displacement this feature is willing to own. */
    fun acceptsDisplacement(dy: Int): Boolean = dy != 0 && abs(dy) <= MAX_DISPLACEMENT_PX

    /**
     * True when one `animateMove` may be taken over: [layoutDeltaPx] is how far that row's layout
     * moved (`toY - fromY`) and [residualPx] the lag the row would then carry (`-dy`).
     *
     * The two are separate on purpose. A seek moves the layout by thousands of pixels, so its jump
     * is refused and the host re-anchors instantly. A *fast* lyric song moves the layout by one line
     * per line change — the jump stays small — while the residual a row carries grows with the line
     * rate, because the 0.45 s spring never gets to finish before the next line lands. Judging the
     * residual against [MAX_DISPLACEMENT_PX] refuses a fast song's moves and hands the rows back to
     * the host mid-song, which reads on the device as the animation being swallowed.
     */
    fun acceptsMove(layoutDeltaPx: Int, residualPx: Float): Boolean {
        if (!residualPx.isFinite()) return false
        if (abs(layoutDeltaPx) > MAX_DISPLACEMENT_PX) return false
        return abs(residualPx) <= MAX_RESIDUAL_PX
    }

    /** `k = 170 * (0.484 / t)^2`, with the settle time `t` in milliseconds. */
    fun stiffnessFor(tempoMs: Float): Float {
        val seconds = tempoMs / 1_000f
        val ratio = (REFERENCE_SETTLE_MS / 1_000f) / seconds
        return REFERENCE_STIFFNESS * ratio * ratio
    }

    /** `c = 2.2 * sqrt(k)`, i.e. `2 zeta sqrt(k m)` for the constants above. */
    fun dampingFor(stiffness: Float): Float = DAMPING_COEFFICIENT * sqrt(stiffness)

    /**
     * The spring for one move, or null when the move is not ours to animate.
     *
     * Null means "hand it back to the host": a zero or oversized displacement, or a non-finite
     * value. The hook treats every null as a refusal, never as "animate with defaults". The
     * parameters themselves are constants, so they never refuse.
     */
    fun springFor(
        displacementPx: Float,
        velocityPxPerSecond: Float = 0f,
    ): LyricLineSpring? {
        if (!displacementPx.isFinite() || displacementPx == 0f) return null
        if (abs(displacementPx) > MAX_DISPLACEMENT_PX) return null
        if (!velocityPxPerSecond.isFinite()) return null
        return tempo.at(displacementPx, velocityPxPerSecond)
    }

    /**
     * The step a frame may advance a spring by.
     *
     * One shared clock drives every row, and a frame that arrives long after the previous one (a
     * stall, a disk read, a GC pause) must not be treated as elapsed time: the row would jump by
     * the whole displacement at once. A negative or non-finite delta is a clock reset.
     */
    fun frameStep(dtMs: Float): Float = when {
        !dtMs.isFinite() || dtMs <= 0f -> 0f
        else -> dtMs.coerceAtMost(MAX_FRAME_STEP_MS)
    }

    /** True when a row at [offsetPx] moving at [velocityPxPerSecond] has arrived and may snap to 0. */
    fun isAtRest(offsetPx: Float, velocityPxPerSecond: Float): Boolean =
        abs(offsetPx) <= SETTLE_EPSILON_PX && abs(velocityPxPerSecond) <= SETTLE_EPSILON_VELOCITY

    /**
     * True when the hook must take the next line change over.
     *
     * Off when its own switch is off, and off when 「歌词效果优化」 is off: that switch owns every
     * lyric *effect* in the module, so the line-change spring follows it rather than running
     * beside it.
     */
    fun isEnabled(settings: ModuleSettings): Boolean =
        settings.lyricLineSpringEnabled && settings.lyricEffectsOptimizationEnabled
}

/**
 * Which curve a spring that is at least critically damped follows.
 *
 * The two are only distinguishable when `zeta > 1` (AMLL's normal mode is 1.1595); below critical
 * both fall back to the same under-damped solution.
 */
enum class LyricLineSpringDecay {
    /**
     * The exact solution of `x'' = (-k x - c x') / m`.
     *
     * Kept as the reference curve — the RK4 cross-check in `LyricLineSpringPolicyTest` validates
     * the closed form against an independent integrator through it. Production never selects it.
     */
    SOLUTION,

    /**
     * AMLL's `zeta >= 1` branch: the critically damped shape at `omega0`, i.e.
     * `(x0 + t(v0 + omega0 x0)) e^(-omega0 t)`.
     *
     * Not a solution of the ODE above — it is the shape AMLL ships, and at the same stiffness it
     * reaches the 1 % gate 1.31x sooner than [SOLUTION] does.
     */
    AMLL,
}

/**
 * One spring's parameters, before a displacement is known: stiffness, damping and curve.
 *
 * A line change re-aims the same tempo at a new displacement as often as Apple Music re-anchors,
 * always through [at] with the row's current position and velocity — that is what keeps the motion
 * continuous across a re-target.
 */
class LyricLineSpringTempo internal constructor(
    val tempoMs: Float,
    val stiffness: Float,
    val damping: Float,
    val decay: LyricLineSpringDecay,
) {
    /** The spring through ([offsetPx], [velocityPxPerSecond]), in pixels against the row's layout. */
    fun at(offsetPx: Float, velocityPxPerSecond: Float = 0f): LyricLineSpring =
        LyricLineSpring(stiffness, damping, offsetPx, velocityPxPerSecond, decay)
}

/**
 * One scalar spring: `x'' = (-k x - c x') / m`, solved in closed form.
 *
 * Closed form rather than per-frame integration is deliberate: the value at `t` must not depend on
 * how many frames were delivered, because a row is written from this single number and any
 * frame-rate dependence would show up as rows "tearing" apart at different refresh rates.
 *
 * [offset] is in pixels and [velocity] in pixels per second, both measured against the row's new
 * layout position; elapsed time is in milliseconds.
 */
class LyricLineSpring internal constructor(
    val stiffness: Float,
    val damping: Float,
    val offset: Float,
    val velocity: Float,
    val decay: LyricLineSpringDecay = LyricLineSpringDecay.SOLUTION,
) {
    /** `sqrt(k/m)`, rad/s. */
    val angularFrequency: Float = sqrt(stiffness / LyricLineSpringPolicy.MASS)

    /** `c / (2m)`, rad/s: the decay rate of the critically damped form. */
    val decayRate: Float = damping / (2f * LyricLineSpringPolicy.MASS)

    /** `zeta = c / (2 sqrt(k m))`; ≈1.1595 for every duration, by construction. */
    val dampingRatio: Float = damping / (2f * sqrt(stiffness * LyricLineSpringPolicy.MASS))

    private val mode: Mode
    private val first: Float
    private val second: Float
    private val slowRate: Float
    private val fastRate: Float
    private val oscillation: Float

    private enum class Mode { OVER_DAMPED, CRITICAL, UNDER_DAMPED }

    init {
        val x0 = offset
        val v0 = velocity
        val omega0 = sqrt(stiffness / LyricLineSpringPolicy.MASS)
        val gamma = damping / (2f * LyricLineSpringPolicy.MASS)
        val betaSquared = gamma * gamma - omega0 * omega0
        val critical = CRITICAL_EPSILON * omega0 * omega0
        when {
            // AMLL's zeta >= 1 branch, zeta > 1 included: one decay rate, no slow/fast pair.
            decay == LyricLineSpringDecay.AMLL && betaSquared >= -critical -> {
                mode = Mode.CRITICAL
                slowRate = -omega0
                fastRate = -omega0
                oscillation = 0f
                // x(t) = (first + second t) e^{-omega0 t}
                first = x0
                second = v0 + omega0 * x0
            }
            betaSquared > critical -> {
                mode = Mode.OVER_DAMPED
                val beta = sqrt(betaSquared)
                // x(t) = first e^{slow t} + second e^{fast t}
                slowRate = -gamma + beta
                fastRate = -gamma - beta
                oscillation = 0f
                first = (v0 - x0 * fastRate) / (slowRate - fastRate)
                second = x0 - first
            }
            betaSquared > -critical -> {
                mode = Mode.CRITICAL
                slowRate = -gamma
                fastRate = -gamma
                oscillation = 0f
                // x(t) = (first + second t) e^{-gamma t}
                first = x0
                second = v0 + gamma * x0
            }
            else -> {
                mode = Mode.UNDER_DAMPED
                slowRate = -gamma
                fastRate = -gamma
                oscillation = sqrt(-betaSquared)
                // x(t) = e^{-gamma t} (first cos wt + second sin wt)
                first = x0
                second = (v0 + gamma * x0) / oscillation
            }
        }
    }

    /** The displacement [elapsedMs] milliseconds after the move was taken over. */
    fun offsetAt(elapsedMs: Float): Float {
        val t = elapsedMs.coerceAtLeast(0f) / 1_000f
        return when (mode) {
            Mode.OVER_DAMPED -> first * exp(slowRate * t) + second * exp(fastRate * t)
            Mode.CRITICAL -> (first + second * t) * exp(slowRate * t)
            Mode.UNDER_DAMPED -> {
                val envelope = exp(slowRate * t)
                envelope * (first * cos(oscillation * t) + second * sin(oscillation * t))
            }
        }
    }

    /** The velocity at [elapsedMs] in pixels per second; carried across a re-target. */
    fun velocityAt(elapsedMs: Float): Float {
        val t = elapsedMs.coerceAtLeast(0f) / 1_000f
        return when (mode) {
            Mode.OVER_DAMPED ->
                first * slowRate * exp(slowRate * t) + second * fastRate * exp(fastRate * t)
            Mode.CRITICAL -> (second + slowRate * (first + second * t)) * exp(slowRate * t)
            Mode.UNDER_DAMPED -> {
                val envelope = exp(slowRate * t)
                val angle = oscillation * t
                envelope * (
                    slowRate * (first * cos(angle) + second * sin(angle)) +
                        oscillation * (-first * sin(angle) + second * cos(angle))
                    )
            }
        }
    }

    /**
     * When |x| first falls to 1% of the starting displacement.
     *
     * Scanned on the closed form (1 ms steps) rather than from an envelope formula, because a
     * re-target carries a non-zero velocity and the envelope is then no longer the slow mode alone.
     * The scan is one line change's worth of work, never one frame's.
     */
    fun settleMs(): Float {
        val start = abs(offset)
        if (start <= LyricLineSpringPolicy.SETTLE_EPSILON_PX) return 0f
        val threshold = start * SETTLE_FRACTION
        var elapsed = 0f
        while (elapsed < SETTLE_SCAN_LIMIT_MS) {
            if (abs(offsetAt(elapsed)) <= threshold) return elapsed
            elapsed += SETTLE_SCAN_STEP_MS
        }
        return SETTLE_SCAN_LIMIT_MS
    }

    /**
     * Absolute deadline after which the row snaps to rest regardless of the curve.
     *
     * The sub-pixel gate alone can take longer than the visible motion for a large displacement;
     * this bounds how long `isRunning()` may keep RecyclerView waiting. The caller caches it: it
     * costs one 1 ms scan of the closed form.
     */
    fun deadlineMs(): Float =
        (settleMs() * DEADLINE_FACTOR).coerceIn(MIN_DEADLINE_MS, SETTLE_SCAN_LIMIT_MS)

    /**
     * The same spring aimed at a new displacement, carrying [velocity].
     *
     * Used when Apple Music re-anchors again while this row is still moving: the row's layout
     * position jumps by exactly the new displacement, so writing the new offset immediately keeps
     * the *displayed* position continuous, and carrying the velocity keeps the motion continuous.
     */
    fun relaunch(offset: Float, velocity: Float = 0f): LyricLineSpring =
        LyricLineSpring(stiffness, damping, offset, velocity, decay)

    companion object {
        /** |x| / |x0| at which the motion counts as settled for timing purposes. */
        const val SETTLE_FRACTION: Float = 0.01f

        private const val SETTLE_SCAN_STEP_MS: Float = 1f
        private const val SETTLE_SCAN_LIMIT_MS: Float = 10_000f
        private const val DEADLINE_FACTOR: Float = 1.6f
        private const val MIN_DEADLINE_MS: Float = 120f

        /** Relative tolerance for "this is (nearly) critically damped". */
        private const val CRITICAL_EPSILON: Float = 1e-4f
    }
}
