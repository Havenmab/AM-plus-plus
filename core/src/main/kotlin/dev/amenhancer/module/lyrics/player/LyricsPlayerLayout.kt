package dev.amenhancer.module.lyrics.player

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure geometry for the self-drawn lyrics page. No Android types: the Android module only
 * converts pixels from `Density` and hands in the measured line height and viewport size.
 *
 * The numbers come from the iOS / LyricsBlossom `SyncedLyricsViewController.Specs` reference:
 * the current line sits at 12 % of the lyric viewport (tablets: a fixed 20 pt), two line
 * boxes are separated by 25 pt at the 34 pt phone type size, and every distance scales with
 * the font-size setting. A short page (landscape phone, window < 600 dp) anchors at 30 %
 * instead of 12 % so the current line stays clear of the top bar.
 *
 * [viewportHeightPx] is the *lyric viewport*, i.e. the area between the top bar and the
 * transport controls. [windowHeightPx] is the whole page height and only feeds the
 * short-window rule.
 */
class LyricsPlayerLayout(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val windowHeightPx: Float = viewportHeightPx,
    val density: Float,
    val isTablet: Boolean = false,
    val fontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    val measuredLineHeightPx: Float = 0f,
    /** Host type size hint (PlayerGeometry.textSizePx); 0 means "use the preset". */
    val hostTextSizePx: Float = 0f,
    /** Host line distance hint (PlayerGeometry.lineSpacingPx); 0 means "derive from the type". */
    val hostLineSpacingPx: Float = 0f,
    /** Host row inset hint; 0 means "use the preset inset". */
    val hostHorizontalInsetPx: Float = 0f,
    /** Host lyric window width; 0 means "use the whole overlay width". */
    val hostContentWidthPx: Float = 0f,
) {
    init {
        require(density > 0f) { "density must be positive" }
        require(viewportWidthPx >= 0f && viewportHeightPx >= 0f && windowHeightPx >= 0f) {
            "viewport must not be negative"
        }
    }

    /** Font-size setting clamped to its supported range (80 %–140 %). */
    val fontScale: Float =
        fontScalePercent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT) / 100f

    /** Preset type size of the platform (34 sp phone, 48 sp tablet). */
    val presetFontSp: Float = if (isTablet) TABLET_FONT_SP else PHONE_FONT_SP

    /** The preset expressed in pixels; also the reference range for the host type-size hint. */
    val presetFontSizePx: Float = presetFontSp * density

    /**
     * Type size in px: the host hint when it has one, otherwise the preset times the setting.
     * A host hint far outside the preset range is treated as unmeasured — the host may hand
     * back a chrome label size by mistake, and a 10 sp lyric page would be unusable.
     */
    val fontSizePx: Float = if (hostTextSizePx > 0f) {
        hostTextSizePx.coerceIn(
            presetFontSizePx * MIN_HOST_TEXT_RATIO,
            presetFontSizePx * MAX_HOST_TEXT_RATIO,
        ) * fontScale
    } else {
        presetFontSizePx * fontScale
    }

    /** The type size in sp, for building a TextUnit-based style from pixels. */
    val fontSp: Float = fontSizePx / density

    /** One line's text height; the renderer passes a measured value once it has one. */
    val lineHeightPx: Float =
        if (measuredLineHeightPx > 0f) measuredLineHeightPx else fontSizePx * DEFAULT_LINE_HEIGHT_RATIO

    /**
     * Space between two line boxes at the current type size (25 pt at 34 pt bold), or the
     * host's line distance when it reports one.
     */
    val lineSpacingPx: Float =
        if (hostLineSpacingPx > 0f) hostLineSpacingPx * fontScale
        else BASE_LINE_SPACING_DP * fontScale * density

    /**
     * Distance between two line centres: what the scroll spring actually moves through.
     * A host `lineSpacingPx` already is a distance between lines, so it is used as the pitch
     * directly; our own 25 pt spacing is a gap and is added to the line box instead.
     */
    val linePitchPx: Float =
        if (hostLineSpacingPx > 0f) {
            lineSpacingPx.coerceIn(lineHeightPx, lineHeightPx * MAX_HOST_PITCH_RATIO)
        } else {
            lineHeightPx + lineSpacingPx
        }

    /** Side inset lyric rows keep from the page edge. */
    val horizontalInsetPx: Float = if (hostHorizontalInsetPx > 0f) {
        hostHorizontalInsetPx
    } else {
        (if (isTablet) TABLET_HORIZONTAL_INSET_DP else PHONE_HORIZONTAL_INSET_DP) * density
    }

    /** Viewport capped by the host's lyric window width, when the host reports one. */
    private val availableWidthPx: Float =
        if (hostContentWidthPx > 0f) minOf(viewportWidthPx, hostContentWidthPx) else viewportWidthPx

    /** Width available to one lyric row; never zero so text measurement stays valid. */
    val contentWidthPx: Float = (availableWidthPx - 2f * horizontalInsetPx).coerceAtLeast(1f)

    /** Distance from the top of the lyric viewport to the centre of the current line. */
    val anchorYPx: Float = when {
        isTablet -> TABLET_ANCHOR_DP * density
        windowHeightPx / density < SHORT_WINDOW_DP -> viewportHeightPx * SHORT_WINDOW_ANCHOR_FRACTION
        else -> viewportHeightPx * PHONE_ANCHOR_FRACTION
    }.coerceIn(0f, viewportHeightPx)

    /**
     * Centre of [lineIndex] for a scroll position of [displayedIndex].
     *
     * [displayedIndex] is a float because the line-change spring animates it; at rest it is
     * the current line index.
     */
    fun lineCenterY(lineIndex: Int, displayedIndex: Float): Float =
        anchorYPx + (lineIndex - displayedIndex) * linePitchPx

    /** Text top for [lineIndex] when the line is [lineHeightPx] tall. */
    fun lineTopY(lineIndex: Int, displayedIndex: Float): Float =
        lineCenterY(lineIndex, displayedIndex) - lineHeightPx * 0.5f

    /**
     * Scroll position for a drag: the row under the finger moves 1:1 with the finger.
     *
     * Positive [dragDistancePx] means the finger moved down, which moves the content down
     * and therefore decreases the displayed index.
     */
    fun displayedIndexForDrag(dragStartIndex: Float, dragDistancePx: Float): Float =
        dragStartIndex - dragDistancePx / linePitchPx

    /** Line under [yPx], or null when there is nothing to hit. */
    fun lineIndexAt(yPx: Float, displayedIndex: Float, lineCount: Int): Int? {
        if (lineCount <= 0) return null
        val raw = displayedIndex + (yPx - anchorYPx) / linePitchPx
        return raw.roundToInt().coerceIn(0, lineCount - 1)
    }

    /** Keeps the scroll position inside the document; the drag itself may overshoot later. */
    fun clampDisplayedIndex(displayedIndex: Float, lineCount: Int): Float =
        if (lineCount <= 0) 0f else displayedIndex.coerceIn(0f, (lineCount - 1).toFloat())

    /**
     * Lines that intersect the viewport for [displayedIndex], widened by [overscan] rows so
     * a spring still paints lines that are about to enter. Always clamped to the document.
     */
    fun visibleLineRange(
        displayedIndex: Float,
        lineCount: Int,
        overscan: Int = DEFAULT_OVERSCAN,
    ): IntRange {
        if (lineCount <= 0) return IntRange.EMPTY
        val slack = overscan.coerceAtLeast(0)
        val firstRaw = displayedIndex + (-anchorYPx - lineHeightPx) / linePitchPx
        val lastRaw = displayedIndex + (viewportHeightPx - anchorYPx + lineHeightPx) / linePitchPx
        val first = (ceil(firstRaw).toInt() - slack).coerceAtLeast(0)
        val last = (floor(lastRaw).toInt() + slack).coerceAtMost(lineCount - 1)
        return if (first > last) IntRange.EMPTY else first..last
    }

    companion object {
        /** Phone type size from the iOS preset. */
        const val PHONE_FONT_SP = 34f

        /** Tablet type size from the iOS preset. */
        const val TABLET_FONT_SP = 48f

        /** Line spacing at the 34 pt phone type size (iOS `lineSpacing = 25`). */
        const val BASE_LINE_SPACING_DP = 25f

        /** The measured line box is usually ~1.2× the type size for the system font. */
        const val DEFAULT_LINE_HEIGHT_RATIO = 1.2f

        const val PHONE_ANCHOR_FRACTION = 0.12f
        const val SHORT_WINDOW_ANCHOR_FRACTION = 0.30f
        const val SHORT_WINDOW_DP = 600f
        const val TABLET_ANCHOR_DP = 20f

        const val PHONE_HORIZONTAL_INSET_DP = 24f
        const val TABLET_HORIZONTAL_INSET_DP = 48f

        /** Host text-size hints outside this ratio of the preset are ignored. */
        const val MIN_HOST_TEXT_RATIO = 0.70f
        const val MAX_HOST_TEXT_RATIO = 1.60f

        /** A host line distance is clamped to 3x the line box so the page cannot collapse. */
        const val MAX_HOST_PITCH_RATIO = 3.0f

        const val MIN_FONT_SCALE_PERCENT = 80
        const val MAX_FONT_SCALE_PERCENT = 140
        const val DEFAULT_FONT_SCALE_PERCENT = 100

        const val DEFAULT_OVERSCAN = 2
    }
}
