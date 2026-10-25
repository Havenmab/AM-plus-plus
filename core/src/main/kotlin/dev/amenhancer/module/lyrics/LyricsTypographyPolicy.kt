package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.ModuleSettings

/**
 * Arithmetic behind the two lyrics typography sliders (font size and line spacing).
 *
 * Deliberately pure and Android-free: the host-side lyrics session captures Apple
 * Music's own metrics and multiplies them through this object, while the slider UI
 * and the schema only deal with percentages.
 *
 * Both scales are percentages of Apple Music's own values. 100 is the neutral
 * value, and neutral is an exact no-op: every helper returns its input unchanged so
 * a default install renders byte-for-byte what the host rendered before. The
 * session is also expected to skip the view updates entirely at 100, which keeps
 * [fontScale]/[spacingScale] returning 1f (multiplying by 1f is not always
 * bit-identical for every float, hence the explicit early returns).
 */
object LyricsTypographyPolicy {
    /** Neutral percentage: keep Apple Music's own size and spacing. */
    const val NEUTRAL_PERCENT: Int = ModuleSettings.DEFAULT_LYRICS_TYPOGRAPHY_PERCENT

    /** A TextView line-spacing pair (extra spacing in px plus the multiplier). */
    data class LineSpacing(val extra: Float, val multiplier: Float)

    /** [percent] clamped to the supported font-size range (50..130). */
    fun fontPercent(percent: Int): Int = percent.coerceIn(
        ModuleSettings.MIN_LYRICS_FONT_SCALE_PERCENT,
        ModuleSettings.MAX_LYRICS_FONT_SCALE_PERCENT,
    )

    /** [percent] clamped to the supported line-spacing range (40..160). */
    fun spacingPercent(percent: Int): Int = percent.coerceIn(
        ModuleSettings.MIN_LYRICS_LINE_SPACING_SCALE_PERCENT,
        ModuleSettings.MAX_LYRICS_LINE_SPACING_SCALE_PERCENT,
    )

    /** True when [percent] asks for Apple Music's own typography. */
    fun isNeutral(percent: Int): Boolean = percent == NEUTRAL_PERCENT

    /** Font-size multiplier: 0.50..1.30, exactly 1f at 100. */
    fun fontScale(percent: Int): Float = fontPercent(percent) / 100f

    /** Line-spacing multiplier: 0.40..1.60, exactly 1f at 100. */
    fun spacingScale(percent: Int): Float = spacingPercent(percent) / 100f

    fun fontScale(settings: ModuleSettings): Float = fontScale(settings.lyricsFontScalePercent)

    fun spacingScale(settings: ModuleSettings): Float = spacingScale(settings.lyricsLineSpacingScalePercent)

    /**
     * True when the host session owns the font size of the lyric text views.
     *
     * Tablet landscape dual pane already derives an absolute type size from the viewport
     * ([dev.amenhancer.module.hook.TabletLyricVisualPolicy]) and writes it to the line *and*
     * word views on every layout pass. Letting the session scale those same views again would
     * apply the slider twice (once per writer), so the dual pane keeps a single font writer
     * and the session only adjusts spacing there.
     */
    fun ownsFontSize(dualPane: Boolean): Boolean = !dualPane

    /** Apple Music's text size scaled by [fontPercent]; the neutral value is returned untouched. */
    fun scaledTextSize(baseSizePx: Float, fontPercent: Int): Float =
        if (isNeutral(fontPercent) || !baseSizePx.isFinite()) {
            baseSizePx
        } else {
            baseSizePx * fontScale(fontPercent)
        }

    /**
     * Apple Music's line spacing scaled by [spacingPercent].
     *
     * Both halves are scaled: the extra spacing (px) and the multiplier, so a host
     * that uses either way of expressing the gap keeps its proportions. Feeding the
     * result back to TextView.setLineSpacing keeps the row heights in sync.
     */
    fun scaledLineSpacing(
        baseExtraPx: Float,
        baseMultiplier: Float,
        spacingPercent: Int,
    ): LineSpacing = if (isNeutral(spacingPercent)) {
        LineSpacing(baseExtraPx, baseMultiplier)
    } else {
        val scale = spacingScale(spacingPercent)
        LineSpacing(
            extra = if (baseExtraPx.isFinite()) baseExtraPx * scale else baseExtraPx,
            multiplier = if (baseMultiplier.isFinite()) baseMultiplier * scale else baseMultiplier,
        )
    }

    /** Vertical row padding scaled by [spacingPercent]; neutral keeps the original px. */
    fun scaledVerticalPadding(basePaddingPx: Float, spacingPercent: Int): Float =
        if (isNeutral(spacingPercent) || !basePaddingPx.isFinite()) {
            basePaddingPx
        } else {
            basePaddingPx * spacingScale(spacingPercent)
        }
}
