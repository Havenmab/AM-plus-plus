package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.LyricLineSpringFeel
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
 * k  = 170 * (0.484 / t)^2      // t = the settle time this feel aims at, in seconds
 * c  = 2.2 * sqrt(k)            // = 2 zeta sqrt(k m), zeta = 1.1595 (just past critical)
 * ```
 *
 * Those numbers are AMLL's (`line-transition-notes/03-amll-spec-v1.md` §2.1): 170 is AMLL's
 * "normal" stiffness and 0.484 s is the t99 AMLL pairs with it. Only `t` differs per feel mode:
 *
 *  * [LyricLineSpringFeel.AMLL] pins `t` to [AMLL_SETTLE_MS] (450 ms), giving `k = 196.8` and
 *    `c = 30.9` — AMLL's own spring, whatever Apple Music computed for this line;
 *  * [LyricLineSpringFeel.HOST] takes `t` from the animator's `$k.e`, which is 乙-1's mapping:
 *    「歌词换行节奏」's multiplier flows through unchanged, because it writes that very field
 *    (multiplying again here would square it).
 *
 * **The AMLL feel also changes the curve, not only `k`.** For `zeta > 1` the exact solution is a
 * pair of exponentials whose slow root decays at `omega0 (zeta - sqrt(zeta^2 - 1))`; a critically
 * damped spring instead follows `(x0 + t(v0 + omega0 x0)) e^(-omega0 t)`. AMLL's `spring.ts` uses
 * the second form for *every* `zeta >= 1` (`soft || 1 <= damping / (2 sqrt(k m))`), so at the same
 * stiffness its block arrives 1.31x sooner at the 1 % gate. That 1.31 is exactly the "t99 = 1.31 T"
 * 乙-1 measured: the old curve was right for the ODE and wrong for AMLL. [LyricLineSpringDecay]
 * therefore carries the choice — AMLL's shape for the AMLL feel, the exact solution (乙-1, and the
 * only thing an RK4 cross-check can validate) for the HOST feel.
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
     * The [LyricLineSpringFeel.AMLL] feel's fixed settle time, in milliseconds.
     *
     * `k = 170 (0.484 / 0.45)^2 = 196.8`, the fast end of AMLL's own k=170..220 normal mode.
     * Deliberately not tied to `$k.e`: the point of this feel is that a line change takes AMLL's
     * time, not the host's.
     */
    const val AMLL_SETTLE_MS: Float = 450f

    /**
     * Beyond this the spring would visibly fly across the screen: a seek or a track change, where
     * Apple Music's instant re-anchor is the correct look. Such moves stay with the host.
     */
    const val MAX_DISPLACEMENT_PX: Int = 1_200

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
     * A duration this module is willing to read out of the animator's move-duration field.
     *
     * The host itself writes 480..792 ms there and 「歌词换行节奏」 rewrites it in place, clamped to
     * 100..1600 ms; anything outside that window is a stale field, a different animator, or a
     * release with another formula — not a tempo — so that move stays with the host. The check
     * guards the [LyricLineSpringFeel.AMLL] feel too, where the value selects no parameter: it is
     * the one signal that this really is the lyric animator's own field.
     */
    const val MIN_DURATION_MS: Long = 100L
    const val MAX_DURATION_MS: Long = 1_600L

    /**
     * The largest layout shift one frame may absorb before it is treated as a re-anchor.
     *
     * A background line growing from nothing moves the rows below it by its own height — tens of
     * pixels over a few hundred milliseconds, i.e. a few pixels per frame. Anything this large
     * inside one frame is a recycled row, a seek or a jump, where the layout moved for a reason we
     * must not absorb.
     */
    const val MAX_LAYOUT_SHIFT_PX: Int = 240

    /** True when a per-frame layout shift of [deltaPx] is a background line growing, not a jump. */
    fun acceptsLayoutShift(deltaPx: Int): Boolean = abs(deltaPx) <= MAX_LAYOUT_SHIFT_PX

    /** True when [dy] is a displacement this feature is willing to own. */
    fun acceptsDisplacement(dy: Int): Boolean = dy != 0 && abs(dy) <= MAX_DISPLACEMENT_PX

    /** True when [durationMs] is a value this module's own writers could have produced. */
    fun isPlausibleDuration(durationMs: Long): Boolean =
        durationMs in MIN_DURATION_MS..MAX_DURATION_MS

    /** `k = 170 * (0.484 / t)^2`, with the settle time `t` in milliseconds. */
    fun stiffnessFor(tempoMs: Float): Float {
        val seconds = tempoMs / 1_000f
        val ratio = (REFERENCE_SETTLE_MS / 1_000f) / seconds
        return REFERENCE_STIFFNESS * ratio * ratio
    }

    /** `c = 2.2 * sqrt(k)`, i.e. `2 zeta sqrt(k m)` for the constants above. */
    fun dampingFor(stiffness: Float): Float = DAMPING_COEFFICIENT * sqrt(stiffness)

    /**
     * The spring one feel mode picks for one line change, or null when the move is not ours.
     *
     * [hostDurationMs] is `$k.e` read at `animateMove` time: the HOST feel's tempo, and for the
     * AMLL feel a plausibility check only.
     *
     * Null means "hand it back to the host": an unusable duration, a non-finite value, or a
     * parameter pair that cannot produce a decaying solution. The hook treats every null as a
     * refusal, never as "animate with defaults".
     */
    fun tempoFor(feel: LyricLineSpringFeel, hostDurationMs: Long): LyricLineSpringTempo? {
        if (!isPlausibleDuration(hostDurationMs)) return null
        val tempoMs = when (feel) {
            LyricLineSpringFeel.AMLL -> AMLL_SETTLE_MS
            LyricLineSpringFeel.HOST -> hostDurationMs.toFloat()
        }
        val stiffness = stiffnessFor(tempoMs)
        if (!stiffness.isFinite() || stiffness <= 0f) return null
        val damping = dampingFor(stiffness)
        if (!damping.isFinite() || damping <= 0f) return null
        val decay = when (feel) {
            LyricLineSpringFeel.AMLL -> LyricLineSpringDecay.AMLL
            LyricLineSpringFeel.HOST -> LyricLineSpringDecay.SOLUTION
        }
        return LyricLineSpringTempo(feel, tempoMs, stiffness, damping, decay)
    }

    /**
     * The spring for one move, or null when the move is not ours to animate.
     *
     * The hook uses [tempoFor] instead, because a per-row spring needs the parameters and the
     * displacement separately; this overload is the single-row arithmetic and its tests.
     */
    fun springFor(
        feel: LyricLineSpringFeel,
        hostDurationMs: Long,
        displacementPx: Float,
        velocityPxPerSecond: Float = 0f,
    ): LyricLineSpring? {
        if (!displacementPx.isFinite() || displacementPx == 0f) return null
        if (abs(displacementPx) > MAX_DISPLACEMENT_PX) return null
        if (!velocityPxPerSecond.isFinite()) return null
        return tempoFor(feel, hostDurationMs)?.at(displacementPx, velocityPxPerSecond)
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

    /** Why a duration was refused, for the one diagnostic line a refusal is allowed to print. */
    fun durationVerdict(durationMs: Long): String = if (isPlausibleDuration(durationMs)) {
        "ok"
    } else {
        "duration=" + durationMs + "ms outside " + MIN_DURATION_MS + ".." + MAX_DURATION_MS + "ms"
    }

    /**
     * The feel the hook must use right now, or null when this feature must do nothing.
     *
     * Off when its own switch is off, and off when 「歌词效果优化」 is off: that switch owns every
     * lyric *effect* in the module, so the line-change spring follows it rather than running
     * beside it.
     */
    fun appliedFeel(settings: ModuleSettings): LyricLineSpringFeel? {
        if (!settings.lyricLineSpringEnabled) return null
        if (!settings.lyricEffectsOptimizationEnabled) return null
        return settings.lyricLineSpringFeel
    }
}

/**
 * Which curve a spring that is at least critically damped follows.
 *
 * The two are only distinguishable when `zeta > 1` (AMLL's normal mode is 1.1595); below critical
 * both fall back to the same under-damped solution.
 */
enum class LyricLineSpringDecay {
    /** The exact solution of `x'' = (-k x - c x') / m`: 乙-1's curve. */
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
 * One feel mode's spring, before a displacement is known: stiffness, damping and curve.
 *
 * A line change re-aims the same [tempo] at a new displacement as often as Apple Music re-anchors,
 * always through [at] with the row's current position and velocity — that is what keeps the
 * motion continuous across a re-target.
 */
class LyricLineSpringTempo internal constructor(
    val feel: LyricLineSpringFeel,
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
