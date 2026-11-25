package dev.amenhancer.module.lyrics

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
 * `ViewPropertyAnimator` that runs for `$k.e` milliseconds. This policy describes the *same*
 * displacement as one scalar spring, so the host hook only has to write one number per frame:
 *
 * ```
 * m  = 0.9
 * k  = 170 * (0.484 / T)^2      // T = the host's own move duration, in seconds
 * c  = 2.2 * sqrt(k)            // = 2 zeta sqrt(k m), zeta = 1.1595 (just past critical)
 * ```
 *
 * Those numbers are AMLL's (`line-transition-notes/03-amll-spec-v1.md` §2.1): 170 is AMLL's
 * "normal" stiffness and 0.484 s is the t99 AMLL pairs with it. Renormalising `k` by
 * `(0.484/T)^2` keeps the *character* of the motion fixed while its *tempo* follows the host.
 * Two consequences worth knowing before reading device logs:
 *
 *  * with this damping the spring reaches 1% of its start after ≈1.31 T, not T (AMLL's own settle
 *    definition is not a plain 1% envelope), and
 *  * 「歌词换行节奏」's multiplier flows through unchanged, because it writes the very field `T`
 *    is read from — multiplying again here would square it.
 *
 * Deliberately pure: no Android, no reflection, no clock. [LyricLineSpringBatch] owns the frame
 * stepping and the exactly-once "finished" bookkeeping, so both are testable on the JVM.
 */
object LyricLineSpringPolicy {
    /** AMLL's moving mass. */
    const val MASS: Float = 0.9f

    /** AMLL's normal damping ratio: just past critical, so there is no visible overshoot. */
    const val DAMPING_RATIO: Float = 1.1595f

    /** AMLL's "normal" stiffness, paired with [REFERENCE_SETTLE_MS]. */
    const val REFERENCE_STIFFNESS: Float = 170f

    /** AMLL's t99 for [REFERENCE_STIFFNESS]; the constant that turns `T` into a tempo knob. */
    const val REFERENCE_SETTLE_MS: Float = 484f

    /** `2 * 1.1595 * sqrt(0.9)`, pre-multiplied: `c = DAMPING_COEFFICIENT * sqrt(k)`. */
    const val DAMPING_COEFFICIENT: Float = 2.2f

    /**
     * Beyond this the spring would visibly fly across the screen: a seek or a track change, where
     * Apple Music's instant re-anchor is the correct look. Such moves stay with the host.
     */
    const val MAX_DISPLACEMENT_PX: Int = 1_200

    /**
     * The frame step is clamped so that a stalled main thread (GC, a disk read, a fling) cannot
     * teleport the block by half a screen in a single frame.
     */
    const val MAX_FRAME_STEP_MS: Float = 100f

    /** Sub-pixel: below one physical pixel of any shipped density, so the final snap is invisible. */
    const val SETTLE_EPSILON_PX: Float = 0.5f

    /**
     * A duration this module is willing to read out of the animator's move-duration field.
     *
     * The host itself writes 480..792 ms there and 「歌词换行节奏」 rewrites it in place, clamped to
     * 100..1600 ms; anything outside that window is a stale field, a different animator, or a
     * release with another formula — not a tempo — so that move stays with the host.
     */
    const val MIN_DURATION_MS: Long = 100L
    const val MAX_DURATION_MS: Long = 1_600L

    /** True when [dy] is a displacement this feature is willing to own. */
    fun acceptsDisplacement(dy: Int): Boolean = dy != 0 && abs(dy) <= MAX_DISPLACEMENT_PX

    /** True when [durationMs] is a value this module's own writers could have produced. */
    fun isPlausibleDuration(durationMs: Long): Boolean =
        durationMs in MIN_DURATION_MS..MAX_DURATION_MS

    /** `k = 170 * (0.484 / T)^2`, with `T` in milliseconds. */
    fun stiffnessFor(durationMs: Float): Float {
        val seconds = durationMs / 1_000f
        val ratio = (REFERENCE_SETTLE_MS / 1_000f) / seconds
        return REFERENCE_STIFFNESS * ratio * ratio
    }

    /** `c = 2.2 * sqrt(k)`, i.e. `2 zeta sqrt(k m)` for the constants above. */
    fun dampingFor(stiffness: Float): Float = DAMPING_COEFFICIENT * sqrt(stiffness)

    /**
     * The spring for one move, or null when the move is not ours to animate.
     *
     * Null means "hand it back to the host": a zero or out-of-range displacement, a duration this
     * module did not write, a non-finite value, or a parameter pair that cannot produce a decaying
     * solution. The hook treats every null as a refusal, never as "animate with defaults".
     */
    fun springFor(
        durationMs: Long,
        displacementPx: Float,
        velocityPxPerSecond: Float = 0f,
    ): LyricLineSpring? {
        if (!isPlausibleDuration(durationMs)) return null
        if (!displacementPx.isFinite() || displacementPx == 0f) return null
        if (abs(displacementPx) > MAX_DISPLACEMENT_PX) return null
        if (!velocityPxPerSecond.isFinite()) return null
        val stiffness = stiffnessFor(durationMs.toFloat())
        if (!stiffness.isFinite() || stiffness <= 0f) return null
        val damping = dampingFor(stiffness)
        if (!damping.isFinite() || damping <= 0f) return null
        return LyricLineSpring(
            durationMs = durationMs.toFloat(),
            stiffness = stiffness,
            damping = damping,
            offset = displacementPx,
            velocity = velocityPxPerSecond,
        )
    }

    /**
     * The step a frame may advance the spring by.
     *
     * One shared clock drives every active batch, and a frame that arrives long after the previous
     * one (a stall, a disk read, a GC pause) must not be treated as elapsed time: the block would
     * jump by the whole displacement at once. A negative or non-finite delta is a clock reset.
     */
    fun frameStep(dtMs: Float): Float = when {
        !dtMs.isFinite() || dtMs <= 0f -> 0f
        else -> dtMs.coerceAtMost(MAX_FRAME_STEP_MS)
    }

    /** Why a duration was refused, for the one diagnostic line a refusal is allowed to print. */
    fun durationVerdict(durationMs: Long): String = if (isPlausibleDuration(durationMs)) {
        "ok"
    } else {
        "duration=${durationMs}ms outside ${MIN_DURATION_MS}..${MAX_DURATION_MS}ms"
    }
}

/**
 * One scalar, almost critically damped spring: `x'' = (-k x - c x') / m`, solved in closed form.
 *
 * Closed form rather than per-frame integration is deliberate: the value at `t` must not depend on
 * how many frames were delivered, because the whole lyric block is written from this single number
 * and any frame-rate dependence would show up as rows "tearing" apart at different refresh rates.
 *
 * `offset` is in pixels and `velocity` in pixels per second, both measured against the row's new
 * layout position; `elapsed` is in milliseconds.
 */
class LyricLineSpring internal constructor(
    val durationMs: Float,
    val stiffness: Float,
    val damping: Float,
    val offset: Float,
    val velocity: Float,
) {
    /** `sqrt(k/m)`, rad/s. */
    val angularFrequency: Float = sqrt(stiffness / LyricLineSpringPolicy.MASS)

    /** `c / (2m)`, rad/s: the decay the solution is built on. */
    val decay: Float = damping / (2f * LyricLineSpringPolicy.MASS)

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
     * The scan is one batch's worth of work, never one frame's.
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
     * Absolute deadline after which the batch snaps to rest regardless of the curve.
     *
     * The sub-pixel gate alone can take longer than the visible motion for a large displacement;
     * this bounds how long `isRunning()` may keep RecyclerView waiting.
     */
    fun deadlineMs(): Float =
        (settleMs() * DEADLINE_FACTOR).coerceIn(MIN_DEADLINE_MS, SETTLE_SCAN_LIMIT_MS)

    /** True when the batch should snap to rest at [elapsedMs] because the motion is over. */
    fun isAtRest(elapsedMs: Float, value: Float): Boolean =
        abs(value) <= LyricLineSpringPolicy.SETTLE_EPSILON_PX || elapsedMs >= deadlineMs()

    /**
     * The same stiffness aimed at a new displacement, carrying [velocity].
     *
     * Used when Apple Music re-anchors again while this row is still moving: the row's layout
     * position jumps by exactly the new displacement, so writing the new offset immediately keeps
     * the *displayed* position continuous, and carrying the velocity keeps the motion continuous.
     */
    fun relaunch(offset: Float, velocity: Float = 0f): LyricLineSpring = LyricLineSpring(
        durationMs = durationMs,
        stiffness = stiffness,
        damping = damping,
        offset = offset,
        velocity = velocity,
    )

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
