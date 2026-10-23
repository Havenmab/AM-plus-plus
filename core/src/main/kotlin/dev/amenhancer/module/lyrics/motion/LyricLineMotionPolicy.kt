package dev.amenhancer.module.lyrics.motion

import dev.amenhancer.module.model.ModuleSettings

/**
 * The numbers behind the 「歌词换行动画」 (iOS-style lyric line motion) feature.
 *
 * Every value here comes from Apple Music's own specs as published by the LyricsBlossom
 * reverse-engineering notes (iPadOS 16.4 `SyncedLyricsViewController.Specs`), cross-checked
 * against the closed-form spring solver recovered from LyricsBlossom's `libmain.so`:
 *
 *  - line change spring: `mass 1.0, stiffness 100, damping 18` → ζ = 0.90, response 0.628 s
 *  - lift spring:        `mass 1.0, stiffness 14,  damping 7`  → ζ = 0.935, response 1.68 s
 *  - per-row delay:      `lineDelay = 0.05 s` (50 ms)
 *  - selected scale:     `emphasizingScaleRange 1.0 ... 1.14`
 *  - unselected scale:   `deselectedTransform 0.98`
 *
 * Pure and Android-free: the host session owns the views, this object owns the arithmetic.
 * 100 % on every slider is Apple's own value.
 */
object LyricLineMotionPolicy {
    /** iOS `lineChangeSpringTimingParameters()`. */
    val lineChange: SpringTiming = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 18.0)

    /** iOS `liftSpringTimingParameters()`: the soft spring that drags the whole block. */
    val lift: SpringTiming = SpringTiming(mass = 1.0, stiffness = 14.0, damping = 7.0)

    /** iOS `lineDelay`: the stagger between neighbouring rows, in milliseconds. */
    const val APPLE_ROW_DELAY_MS: Long = 50

    /** Rows further away than this share the last delay step. */
    const val MAX_STAGGER_ROWS: Int = 3

    /** Refresh ticker period of the host session, mirroring the typography session. */
    const val SETTINGS_POLL_MILLIS: Long = 700

    fun isEnabled(settings: ModuleSettings): Boolean = settings.lyricLineMotionEnabled

    fun durationPercent(settings: ModuleSettings): Int = settings.lyricLineMotionDurationPercent
        .coerceIn(
            ModuleSettings.MIN_LYRIC_LINE_MOTION_DURATION_PERCENT,
            ModuleSettings.MAX_LYRIC_LINE_MOTION_DURATION_PERCENT,
        )

    fun staggerMs(settings: ModuleSettings): Long = settings.lyricLineMotionStaggerMs
        .coerceIn(
            ModuleSettings.MIN_LYRIC_LINE_MOTION_STAGGER_MS,
            ModuleSettings.MAX_LYRIC_LINE_MOTION_STAGGER_MS,
        )
        .toLong()

    /** 1.00 ... 1.14 multiplier applied to the highlighted row (`emphasizingScaleRange`). */
    fun emphasisScale(settings: ModuleSettings): Float = settings.lyricLineMotionEmphasisPercent
        .coerceIn(
            ModuleSettings.MIN_LYRIC_LINE_MOTION_EMPHASIS_PERCENT,
            ModuleSettings.MAX_LYRIC_LINE_MOTION_EMPHASIS_PERCENT,
        ) / 100f

    /** Apple's own row scale for an unselected row (`deselectedTransform`, 0.98). */
    const val DESELECTED_SCALE: Float = 0.98f

    /** Animation duration in milliseconds for [spring], scaled by the duration slider. */
    fun durationMillis(
        settings: ModuleSettings,
        spring: SpringTiming = lineChange,
        threshold: Double = SpringTiming.DEFAULT_THRESHOLD,
    ): Long {
        val seconds = spring.durationSeconds(threshold) * (durationPercent(settings) / 100.0)
        return (seconds * 1000.0).toLong().coerceAtLeast(MIN_DURATION_MILLIS)
    }

    /** Start delay for a row [rowDistance] rows away from the highlighted row. */
    fun rowDelayMillis(settings: ModuleSettings, rowDistance: Int): Long {
        val steps = rowDistance.coerceIn(0, MAX_STAGGER_ROWS)
        return staggerMs(settings) * steps
    }

    /**
     * Normalised spring progress in 0..1 for [elapsedMillis] of wall-clock time.
     *
     * The duration slider compresses or stretches the time axis instead of changing the
     * spring itself, so 150 % samples the identical curve half as fast. The host session
     * passes the animator's real elapsed time and gets the scaled curve back.
     */
    fun progress(settings: ModuleSettings, elapsedMillis: Long): Float {
        val scale = durationPercent(settings) / 100.0
        return lineChange.value(elapsedMillis / 1000.0 / scale).toFloat()
    }

    /** True when every value is Apple's own, i.e. the feature can be a pure passthrough. */
    fun isAppleDefault(settings: ModuleSettings): Boolean =
        durationPercent(settings) == ModuleSettings.DEFAULT_LYRIC_LINE_MOTION_DURATION_PERCENT &&
            staggerMs(settings) == ModuleSettings.DEFAULT_LYRIC_LINE_MOTION_STAGGER_MS.toLong() &&
            emphasisScale(settings) ==
            ModuleSettings.DEFAULT_LYRIC_LINE_MOTION_EMPHASIS_PERCENT / 100f

    private const val MIN_DURATION_MILLIS = 40L
}
