package dev.amenhancer.module.lyrics.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Closed-form spring timing, i.e. the same model Apple's `UISpringTimingParameters`
 * uses and the one LyricsBlossom re-implements for its own renderer.
 *
 * The solver is analytic rather than frame-by-frame integrated, so `value(t)` is a
 * pure function of time: the caller samples it per frame and every device renders the
 * exact same curve. The three regimes are
 *
 *  - under-damped  (`ζ < 1`): `x = 1 - e^(-ζωt)·(cos(ω_d t) + (ζω/ω_d)·sin(ω_d t))`
 *  - critical      (`ζ = 1`): `x = 1 - e^(-ωt)·(1 + ωt)`
 *  - over-damped   (`ζ > 1`): `x = 1 - e^(-ζωt)·(cosh(ω_d' t) + (ζω/ω_d')·sinh(ω_d' t))`
 *
 * where `ω = sqrt(stiffness / mass)` and `ζ = damping / (2·sqrt(stiffness·mass))`.
 *
 * This class is deliberately Android-free (core JVM module): the host session wraps it
 * in a `TimeInterpolator`/`ValueAnimator` and the unit tests pin the curve values.
 */
class SpringTiming(
    val mass: Double,
    val stiffness: Double,
    val damping: Double,
) {
    init {
        require(mass > 0.0) { "mass must be positive" }
        require(stiffness > 0.0) { "stiffness must be positive" }
        require(damping >= 0.0) { "damping must not be negative" }
    }

    /** `ζ = damping / (2·sqrt(stiffness·mass))`; 1 is critically damped. */
    val dampingRatio: Double = damping / (2.0 * sqrt(stiffness * mass))

    /** Natural frequency `ω = sqrt(stiffness / mass)` in rad/s. */
    val naturalFrequency: Double = sqrt(stiffness / mass)

    /** Undamped period in seconds (`2π/ω`), the number iOS calls "response". */
    val responseSeconds: Double = 2.0 * Math.PI / naturalFrequency

    /**
     * Normalised spring value at [seconds], mapped from 0 to 1 (with an overshoot
     * above 1 while under-damped). Time is clamped at 0.
     */
    fun value(seconds: Double): Double = value(seconds, from = 0.0, to = 1.0)

    /** Spring value at [seconds], mapped from [from] to [to]. */
    fun value(seconds: Double, from: Double, to: Double): Double {
        val t = seconds.coerceAtLeast(0.0)
        val z = dampingRatio
        val w = naturalFrequency
        val progress = when {
            abs(z - 1.0) <= CRITICAL_EPSILON -> 1.0 - exp(-w * t) * (1.0 + w * t)
            z < 1.0 -> {
                val damped = w * sqrt(1.0 - z * z)
                1.0 - exp(-z * w * t) * (cos(damped * t) + (z * w / damped) * sin(damped * t))
            }
            else -> {
                val damped = w * sqrt(z * z - 1.0)
                1.0 - exp(-z * w * t) * (cosh(damped * t) + (z * w / damped) * sinh(damped * t))
            }
        }
        return from + (to - from) * progress
    }

    /**
     * Seconds until the spring stays within [threshold] of its target.
     *
     * Apple gives no duration for a spring: the animation ends when the displacement
     * becomes imperceptible. The default 0.01 (1 %) is the value the lyrics row motion
     * uses, because on a 50 px row move 1 % is half a pixel. Under-damped springs use
     * the analytic envelope, the other two regimes are scanned at a fine step and then
     * refined by bisection, so the result is deterministic across devices.
     */
    fun durationSeconds(threshold: Double = DEFAULT_THRESHOLD): Double {
        require(threshold > 0.0 && threshold < 1.0) { "threshold must be in (0, 1)" }
        val z = dampingRatio
        val w = naturalFrequency
        if (z in CRITICAL_EPSILON..(1.0 - CRITICAL_EPSILON)) {
            // Envelope |1 - x| <= e^(-ζωt)/sqrt(1-ζ²) for small amplitudes.
            val envelope = threshold * sqrt(1.0 - z * z)
            return (-ln(envelope) / (z * w)).coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        }
        var low = 0.0
        var high = 0.0
        val step = 1.0 / (w * SCAN_SAMPLES_PER_PERIOD)
        while (high < MAX_DURATION_SECONDS) {
            high += step
            if (abs(1.0 - value(high)) <= threshold) break
        }
        if (high >= MAX_DURATION_SECONDS) return MAX_DURATION_SECONDS
        low = (high - step).coerceAtLeast(0.0)
        repeat(BISECTION_STEPS) {
            val mid = (low + high) / 2.0
            if (abs(1.0 - value(mid)) <= threshold) high = mid else low = mid
        }
        return high
    }

    /**
     * The duration the LyricsBlossom binary itself reports for this spring
     * (`TimingFunction::duration()`, `0x28423c8` in libmain.so 8.1):
     * under-damped `max(ln(100) / (ζ·ω), 4 / ω_d)`; critical/over-damped
     * `2·m·ln(100) / c`.
     *
     * This is a *nominal* duration, not a settle time: for the line-change spring
     * (1.0 / 100 / 18) it is 0.918 s, while [durationSeconds] (1 % envelope) is about
     * 0.604 s. Callers that end an animation on a clock use this value; callers that
     * snap when the displacement becomes imperceptible use [durationSeconds].
     * Evidence: `.prep/lyricsblossom-re/FINDINGS.md` §2.4.
     */
    fun nominalDurationSeconds(): Double {
        val z = dampingRatio
        val w = naturalFrequency
        val seconds = if (z in CRITICAL_EPSILON..(1.0 - CRITICAL_EPSILON)) {
            val damped = w * sqrt(1.0 - z * z)
            maxOf(ln(NOMINAL_SETTLE_DIVISOR) / (z * w), 4.0 / damped)
        } else {
            if (damping <= 0.0) return MAX_DURATION_SECONDS
            2.0 * mass * ln(NOMINAL_SETTLE_DIVISOR) / damping
        }
        return seconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
    }

    /** A plain multiply used by the settings slider (100 % = Apple's own timing). */
    fun scaledDurationSeconds(percent: Int, threshold: Double = DEFAULT_THRESHOLD): Double =
        durationSeconds(threshold) * (percent.coerceAtLeast(1) / 100.0)

    override fun toString(): String =
        "SpringTiming(mass=$mass, stiffness=$stiffness, damping=$damping, " +
            "ratio=$dampingRatio, frequency=$naturalFrequency)"

    companion object {
        /** Displacement that still counts as "settled" for the row motion. */
        const val DEFAULT_THRESHOLD: Double = 0.01

        private const val CRITICAL_EPSILON = 1e-4
        private const val MIN_DURATION_SECONDS = 0.016
        private const val MAX_DURATION_SECONDS = 10.0
        /** `ln(100)` as stored at `.rodata 0x1dd190` in the LyricsBlossom binary. */
        private const val NOMINAL_SETTLE_DIVISOR = 100.0
        private const val SCAN_SAMPLES_PER_PERIOD = 200
        private const val BISECTION_STEPS = 24
    }
}
