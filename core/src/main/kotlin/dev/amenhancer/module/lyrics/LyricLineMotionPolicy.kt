package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.ModuleSettings

/**
 * Arithmetic behind the 「歌词换行节奏」 slider.
 *
 * Apple Music 7.0.3 computes the lyric list's move duration from the gap between the current
 * line's end and the next line's begin, and writes it into the lyrics item animator's own
 * duration field right before the animator consumes it. This object only multiplies that
 * already-computed value; it never invents a duration and never touches the way the list moves.
 *
 * Deliberately pure and Android-free, so the host hook stays a two-line caller and the whole
 * behaviour (including the 0.5 / 1.0 / 2.0 boundaries) is unit-testable in :core.
 *
 * Neutral is an exact no-op: [appliedScale] returns null at 1.0 and [scaledDurationMs] returns
 * null in that case, so a default install keeps Apple Music's own timing bit-for-bit.
 */
object LyricLineMotionPolicy {
    /** Slowest multiplier the slider offers. */
    const val MIN_SCALE: Float = 0.5f

    /** Fastest multiplier the slider offers. */
    const val MAX_SCALE: Float = 2.0f

    /** Apple Music's own timing: the slider's default and its exact no-op. */
    const val NEUTRAL_SCALE: Float = 1.0f

    /**
     * The window Apple Music 7.0.3 itself writes into the move-duration field
     * (`round(lerp(480, 800, clamp(gap, 200, 750))`, i.e. 480..792 ms).
     *
     * A value outside it did not come from that calculation — a stale default, a field of a
     * different animator, or a future release with another formula — and must not be scaled.
     */
    const val MIN_HOST_DURATION_MS: Long = 200L
    const val MAX_HOST_DURATION_MS: Long = 800L

    /** Output guard, so a degenerate multiply can never hand the animator a useless duration. */
    const val MIN_DURATION_MS: Long = 100L
    const val MAX_DURATION_MS: Long = 1_600L

    /** [scale] clamped to 0.5..2.0; NaN (a corrupt store) falls back to neutral. */
    fun normalizeScale(scale: Float): Float = when {
        scale.isNaN() -> NEUTRAL_SCALE
        else -> scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** True when [scale] asks for Apple Music's own timing. */
    fun isNeutral(scale: Float): Boolean = normalizeScale(scale) == NEUTRAL_SCALE

    /**
     * Storage form of [scale] (a percentage).
     *
     * The embedded settings store persists booleans, ints, longs and strings only, so the
     * multiplier is stored as percent: exactly representable in both directions and the form
     * every other slider in this module already uses.
     */
    fun encodePercent(scale: Float): Int = Math.round(normalizeScale(scale) * 100f)

    /** Inverse of [encodePercent]; an absent or out-of-range stored percentage is clamped/defaulted. */
    fun decodePercent(percent: Int?): Float = when (percent) {
        null -> NEUTRAL_SCALE
        else -> percent.coerceIn(Math.round(MIN_SCALE * 100f), Math.round(MAX_SCALE * 100f)) / 100f
    }

    /**
     * The multiplier this feature actually applies, or null when it must do nothing.
     *
     * Off when its own switch is off, and off when 「歌词效果优化」 is off: that switch owns every
     * lyric *effect* in the module (centring, wrapping, karaoke), so changing the line-change
     * rhythm has to follow it rather than run beside it.
     */
    fun appliedScale(settings: ModuleSettings): Float? {
        if (!settings.lyricLineMotionEnabled) return null
        if (!settings.lyricEffectsOptimizationEnabled) return null
        val scale = normalizeScale(settings.lyricLineMotionDurationScale)
        return scale.takeIf { !isNeutral(it) }
    }

    /** True when [durationMs] is a value Apple Music's own line calculation could have written. */
    fun isHostDuration(durationMs: Long): Boolean =
        durationMs in MIN_HOST_DURATION_MS..MAX_HOST_DURATION_MS

    /**
     * The duration to write back in place of [hostDurationMs], or null when nothing must change.
     *
     * One multiply, applied to the host's freshly recomputed value — never to whatever the field
     * currently holds. Returning null for a neutral multiplier (and for a result that would equal
     * the host's value) is what makes the host hook idempotent: it writes this result exactly once
     * per host recalculation, so no caller can ever compound the scale.
     */
    fun scaledDurationMs(hostDurationMs: Long, scale: Float): Long? {
        if (!isHostDuration(hostDurationMs)) return null
        val normalized = normalizeScale(scale)
        if (isNeutral(normalized)) return null
        val scaled = Math.round(hostDurationMs * normalized.toDouble())
        val bounded = scaled.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return bounded.takeIf { it != hostDurationMs }
    }
}
