package dev.amenhancer.module.lyrics.player

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which composition the page uses. The LyricsBlossom reference page is a two-pane tablet
 * layout (cover + transport on the left, lyrics on the right); phones get a single column
 * with a compact cover header.
 */
enum class LyricsPlayerLayoutMode {
    TwoPane,
    SingleColumn;

    companion object {
        /** 600 dp is the platform tablet breakpoint. */
        fun forViewport(smallestWidthDp: Int): LyricsPlayerLayoutMode =
            if (smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP) TwoPane else SingleColumn

        const val TABLET_SMALLEST_WIDTH_DP = 600
    }
}

/**
 * Pure geometry of the v2 player page (LyricsBlossom layout).
 *
 * Two-pane values are the measurements from the LyricsBlossom 8.1 reference screenshot
 * (3200 x 2136): cover 1165 x 1165 at (133, 213), left column ends at 40.6 % of the width,
 * the lyrics column starts at 48.9 % and is left aligned, the current line's centre sits at
 * 36.5 % of the height, the main line pitch is 284.6 px, and the translation sits 116.5 px
 * below the main line's centre. Everything is a fraction of the viewport, so the same
 * composition scales to any tablet; phones use their own single-column constants.
 *
 * RE: sections 3 and 4 of .prep/lyricsblossom-re/VISUAL-SPEC.md.
 */
class LyricsPlayerColumns(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val density: Float,
    val mode: LyricsPlayerLayoutMode,
    val fontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
) {
    init {
        require(density > 0f) { "density must be positive" }
        require(viewportWidthPx >= 0f && viewportHeightPx >= 0f) { "viewport must not be negative" }
    }

    val twoPane: Boolean get() = mode == LyricsPlayerLayoutMode.TwoPane

    val fontScale: Float =
        fontScalePercent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT) / 100f

    // ---- cover -------------------------------------------------------------------------

    val coverLeftPx: Float
    val coverTopPx: Float
    val coverSizePx: Float
    val coverCornerRadiusPx: Float

    // ---- left column (cover + metadata + transport) ------------------------------------

    val leftColumnLeftPx: Float get() = coverLeftPx
    val leftColumnRightPx: Float get() = coverLeftPx + coverSizePx
    val leftColumnWidthPx: Float get() = coverSizePx
    val leftColumnCenterXPx: Float get() = coverLeftPx + coverSizePx * 0.5f

    val titleTopPx: Float
    val artistTopPx: Float
    val menuCenterYPx: Float
    val progressCenterYPx: Float
    val timesCenterYPx: Float
    val transportCenterYPx: Float
    val volumeCenterYPx: Float
    val footerCenterYPx: Float

    // ---- lyrics column -----------------------------------------------------------------

    val lyricsLeftPx: Float
    val lyricsRightPx: Float
    val lyricsWidthPx: Float get() = lyricsRightPx - lyricsLeftPx

    /** RE: task-8 §10 — the layout core works in dp, so the page width in dp is the input. */
    val pageWidthDp: Float get() = viewportWidthPx / density

    /**
     * RE: task-8 §10 — the reference device takes the compact branch:
     * insetX = w/2 − 18 dp, contentW = w − 54 dp, columnW = contentW − insetX, and a main
     * line takes 95 % of that column. Everything is in dp; the px value is just × display
     * scale. The inset explains why the lyrics start just past the page centre.
     */
    val columnWidthPx: Float get() = lyricsWidthPx

    val lineWidthPx: Float get() = columnWidthPx * LINE_WIDTH_RATIO

    /** RE: subsidiary rows take 0.80 of the column and then divide by spec+0x4c (0.7). */
    val subsidiaryLineWidthPx: Float get() = columnWidthPx * SUBSIDIARY_LINE_WIDTH_RATIO / SUBSIDIARY_SPACING_DIVISOR

    val lyricsTopPx: Float
    val lyricsBottomPx: Float
    val lyricsHeightPx: Float get() = lyricsBottomPx - lyricsTopPx

    /** Absolute y (from the page top) of the current line's centre. RE: 36.5 % of height. */
    val anchorYPx: Float

    // ---- lyrics typography -------------------------------------------------------------

    /**
     * Current line type size. RE: half-height 81 px and em about 105 px on the 3200 px
     * reference, i.e. ~4.94 % of the short side; the current line is not scaled up (1.14
     * does not exist in the binary), only bold and unblurred.
     */
    val currentFontPx: Float

    /** Other lines are ~8 % smaller (reference half-height 75 px vs 81 px). */
    val otherFontPx: Float

    /** Translation under the main line: reference glyph height 45-49 px. */
    val translationFontPx: Float

    /** Main line pitch. RE: 284.6 px on the reference = 13.3 % of height. */
    val linePitchPx: Float

    /** Distance from the main line's centre to the translation's centre. RE: 116.5 px. */
    val translationCenterOffsetPx: Float

    /** Translation is drawn at this alpha (RE about 0.30). */
    val translationAlpha: Float

    // ---- left column typography --------------------------------------------------------

    val titleFontPx: Float
    val artistFontPx: Float
    val timesFontPx: Float
    val footerFontPx: Float
    val transportIconPx: Float
    val playIconPx: Float
    val progressBarHeightPx: Float
    val progressThumbRadiusPx: Float
    val volumeBarHeightPx: Float

    // ---- phone header / bottom block ---------------------------------------------------

    val headerHeightPx: Float
    val phoneCoverSizePx: Float
    val phoneCoverLeftPx: Float
    val phoneCoverTopPx: Float
    val headerTextLeftPx: Float

    val bottomBlockHeightPx: Float
    val bottomBlockTopPx: Float

    init {
        if (twoPane) {
            coverLeftPx = viewportWidthPx * COVER_LEFT_FRACTION
            coverTopPx = viewportHeightPx * COVER_TOP_FRACTION
            coverSizePx = viewportWidthPx * COVER_SIZE_FRACTION

            titleTopPx = viewportHeightPx * TITLE_TOP_FRACTION
            artistTopPx = viewportHeightPx * ARTIST_TOP_FRACTION
            menuCenterYPx = viewportHeightPx * MENU_CENTER_FRACTION
            progressCenterYPx = viewportHeightPx * PROGRESS_CENTER_FRACTION
            timesCenterYPx = viewportHeightPx * TIMES_CENTER_FRACTION
            transportCenterYPx = viewportHeightPx * TRANSPORT_CENTER_FRACTION
            volumeCenterYPx = viewportHeightPx * VOLUME_CENTER_FRACTION
            footerCenterYPx = viewportHeightPx * FOOTER_CENTER_FRACTION

            // Compact branch in dp (RE §10): insetX = w/2 - 18, contentW = w - 54.
            val insetX = pageWidthDp * 0.5f - COMPACT_INSET_HALF_DP
            val columnDp = (pageWidthDp - COMPACT_CONTENT_INSET_DP) - insetX
            lyricsLeftPx = insetX * density
            lyricsRightPx = lyricsLeftPx + columnDp * density
            lyricsTopPx = 0f
            lyricsBottomPx = viewportHeightPx
            anchorYPx = viewportHeightPx * ANCHOR_FRACTION

            currentFontPx = min(viewportWidthPx, viewportHeightPx) * TABLET_FONT_SHORT_SIDE_RATIO * fontScale
            otherFontPx = currentFontPx * OTHER_FONT_RATIO
            translationFontPx = currentFontPx * TRANSLATION_FONT_RATIO
            // RE: C-level fallback (task-8 closing summary) — the real pitch is hidden behind
            // a runtime function pointer, so use the measured 102.7 dp; the user font setting
            // still scales it so a larger lyric does not overlap.
            linePitchPx = LINE_PITCH_DP * density * fontScale
            translationCenterOffsetPx = TRANSLATION_CENTER_DP * density * fontScale
            translationAlpha = TRANSLATION_ALPHA

            titleFontPx = currentFontPx * TITLE_FONT_RATIO
            artistFontPx = currentFontPx * ARTIST_FONT_RATIO
            timesFontPx = currentFontPx * TIMES_FONT_RATIO
            footerFontPx = currentFontPx * FOOTER_FONT_RATIO
            transportIconPx = currentFontPx * TRANSPORT_ICON_RATIO
            playIconPx = currentFontPx * PLAY_ICON_RATIO
            progressBarHeightPx = currentFontPx * BAR_HEIGHT_RATIO
            progressThumbRadiusPx = currentFontPx * THUMB_RADIUS_RATIO
            volumeBarHeightPx = currentFontPx * BAR_HEIGHT_RATIO

            coverCornerRadiusPx = currentFontPx * COVER_CORNER_RATIO

            headerHeightPx = 0f
            phoneCoverSizePx = 0f
            phoneCoverLeftPx = 0f
            phoneCoverTopPx = 0f
            headerTextLeftPx = 0f
            bottomBlockHeightPx = 0f
            bottomBlockTopPx = viewportHeightPx
        } else {
            headerHeightPx = PHONE_HEADER_DP * density
            phoneCoverSizePx = PHONE_COVER_DP * density
            phoneCoverLeftPx = PHONE_HORIZONTAL_INSET_DP * density
            phoneCoverTopPx = (headerHeightPx - phoneCoverSizePx) * 0.5f
            headerTextLeftPx = phoneCoverLeftPx + phoneCoverSizePx + PHONE_HEADER_GAP_DP * density
            coverLeftPx = phoneCoverLeftPx
            coverTopPx = phoneCoverTopPx
            coverSizePx = phoneCoverSizePx

            titleTopPx = phoneCoverTopPx
            artistTopPx = phoneCoverTopPx + phoneCoverSizePx * 0.58f
            menuCenterYPx = headerHeightPx * 0.5f
            progressCenterYPx = 0f
            timesCenterYPx = 0f
            transportCenterYPx = 0f
            volumeCenterYPx = 0f
            footerCenterYPx = 0f

            bottomBlockHeightPx = (PHONE_PROGRESS_DP + PHONE_TRANSPORT_DP + PHONE_BOTTOM_PAD_DP) * density
            bottomBlockTopPx = viewportHeightPx - bottomBlockHeightPx
            lyricsLeftPx = PHONE_HORIZONTAL_INSET_DP * density
            lyricsRightPx = viewportWidthPx - PHONE_HORIZONTAL_INSET_DP * density
            lyricsTopPx = headerHeightPx
            lyricsBottomPx = bottomBlockTopPx - PHONE_LYRICS_GAP_DP * density
            anchorYPx = lyricsTopPx + (lyricsBottomPx - lyricsTopPx) * PHONE_ANCHOR_FRACTION

            currentFontPx = PHONE_CURRENT_FONT_SP * fontScale * density
            otherFontPx = currentFontPx * OTHER_FONT_RATIO
            translationFontPx = currentFontPx * TRANSLATION_FONT_RATIO
            // Phone keeps the proportional spacing (the reference screenshot is a tablet).
            linePitchPx = currentFontPx * PHONE_LINE_PITCH_RATIO
            translationCenterOffsetPx = currentFontPx * PHONE_TRANSLATION_CENTER_RATIO
            translationAlpha = TRANSLATION_ALPHA

            titleFontPx = PHONE_TITLE_SP * density
            artistFontPx = PHONE_ARTIST_SP * density
            timesFontPx = PHONE_TIMES_SP * density
            footerFontPx = PHONE_TIMES_SP * density
            transportIconPx = PHONE_TRANSPORT_ICON_DP * density
            playIconPx = PHONE_PLAY_ICON_DP * density
            progressBarHeightPx = PHONE_BAR_HEIGHT_DP * density
            progressThumbRadiusPx = PHONE_THUMB_DP * density
            volumeBarHeightPx = PHONE_BAR_HEIGHT_DP * density

            coverCornerRadiusPx = PHONE_COVER_CORNER_DP * density
        }
    }

    /** Centre y of line [lineIndex] for a scroll position [displayedIndex] (line units). */
    fun lineCenterY(lineIndex: Int, displayedIndex: Float): Float =
        anchorYPx + (lineIndex - displayedIndex) * linePitchPx

    fun clampDisplayedIndex(displayedIndex: Float, lineCount: Int): Float =
        if (lineCount <= 0) 0f else displayedIndex.coerceIn(0f, (lineCount - 1).toFloat())

    /** Line under [yPx] (absolute page coordinates). */
    fun lineIndexAt(yPx: Float, displayedIndex: Float, lineCount: Int): Int? {
        if (lineCount <= 0) return null
        val raw = displayedIndex + (yPx - anchorYPx) / linePitchPx
        return raw.roundToInt().coerceIn(0, lineCount - 1)
    }

    // ---- progressive blur / brightness -------------------------------------------------

    /**
     * Gaussian sigma for a line [distance] rows from the current one.
     * RE: sigma(d) about (7 + 4*d) px on the 3200 px reference (d = 1: 11 px, d = 2: 14 px,
     * d = 3: 18.7 px); expressed as a fraction of the viewport width so the same optical
     * blur survives any device size.
     */
    fun blurSigmaPx(distance: Int): Float {
        // The current line itself is painted sharp (sigma 0); every other line is blurred.
        if (distance <= 0) return 0f
        val step = distance.coerceIn(0, MAX_BLUR_DISTANCE)
        // RE §10/§11 - the layout is in dp, so sigma is too: 7+4d px at display scale 2.77
        // is about 2.5+1.4d dp. The px cap only guards against extreme distances.
        val sigmaDp = BLUR_BASE_DP + BLUR_STEP_DP * step
        return (sigmaDp * density).coerceAtMost(viewportWidthPx * MAX_BLUR_WIDTH_FRACTION)
    }

    /**
     * Alpha of a non-current line. RE: after de-blurring the reference, neighbours are
     * painted at a constant alpha of 0.45-0.52 - the apparent "dimmer with distance" is
     * produced by the blur itself, not by an alpha ramp. Sung lines read marginally dimmer.
     */
    fun lineAlpha(distance: Int, sung: Boolean): Float = when {
        distance == 0 -> 1f
        sung -> SUNGLINE_ALPHA
        else -> NEIGHBOUR_ALPHA
    }

    /** RE: the current line is not scaled up; unselected lines rest at 0.98. */
    fun lineScale(distance: Int): Float = if (distance == 0) 1f else DESELECTED_SCALE

    companion object {
        // ---- two-pane fractions (LyricsBlossom 8.1 reference, 3200 x 2136) -------------
        const val REFERENCE_WIDTH_PX = 3200f

        const val COVER_LEFT_FRACTION = 133f / 3200f
        const val COVER_TOP_FRACTION = 213f / 2136f
        const val COVER_SIZE_FRACTION = 1165f / 3200f

        const val TITLE_TOP_FRACTION = 1508f / 2136f
        const val ARTIST_TOP_FRACTION = 1556f / 2136f
        const val MENU_CENTER_FRACTION = 1536f / 2136f
        const val PROGRESS_CENTER_FRACTION = 1648f / 2136f
        const val TIMES_CENTER_FRACTION = 1690f / 2136f
        const val TRANSPORT_CENTER_FRACTION = 1775f / 2136f
        const val VOLUME_CENTER_FRACTION = 1897f / 2136f
        const val FOOTER_CENTER_FRACTION = 2041f / 2136f

        const val ANCHOR_FRACTION = 780f / 2136f

        /** RE §10 compact branch: insetX = w/2 - 18 dp, contentW = w - 54 dp. */
        const val COMPACT_INSET_HALF_DP = 18f
        const val COMPACT_CONTENT_INSET_DP = 54f

        /** RE: Specs +0x440 = 0.95 (main line width ratio), +0x444 = 0.80 (subsidiary). */
        const val LINE_WIDTH_RATIO = 0.95f
        const val SUBSIDIARY_LINE_WIDTH_RATIO = 0.80f
        const val SUBSIDIARY_SPACING_DIVISOR = 0.7f

        /**
         * RE: current line em is about 100 px on a 2136 px short side (FreeType comparison
         * against the real Hiragino faces). The current line is NOT enlarged: the tiny
         * difference between lines is weight (W7 vs W5), not size.
         */
        const val TABLET_FONT_SHORT_SIDE_RATIO = 100f / 2136f
        const val OTHER_FONT_RATIO = 1.0f
        const val TRANSLATION_FONT_RATIO = 0.54f

        /** RE: measured main pitch 284.6 px at display scale 2.77 = 102.7 dp (C level). */
        const val LINE_PITCH_DP = 102.7f

        /** RE: translation centre 116.5 px below the main line at scale 2.77 = 42.06 dp. */
        const val TRANSLATION_CENTER_DP = 42.06f

        const val TITLE_FONT_RATIO = 0.46f
        const val ARTIST_FONT_RATIO = 0.46f
        const val TIMES_FONT_RATIO = 0.24f
        const val FOOTER_FONT_RATIO = 0.30f
        const val TRANSPORT_ICON_RATIO = 0.70f
        const val PLAY_ICON_RATIO = 0.66f
        const val BAR_HEIGHT_RATIO = 0.12f
        const val THUMB_RADIUS_RATIO = 0.13f
        const val COVER_CORNER_RATIO = 12f / 100f

        const val NEIGHBOUR_ALPHA = 0.50f
        const val SUNGLINE_ALPHA = 0.45f
        const val TRANSLATION_ALPHA = 0.30f
        const val DESELECTED_SCALE = 0.98f

        /** RE §10: sigma(d) = (7 + 4d) px at display scale 2.77, i.e. (2.5 + 1.4d) dp. */
        const val BLUR_BASE_DP = 2.5f
        const val BLUR_STEP_DP = 1.4f
        const val MAX_BLUR_DISTANCE = 4

        /** RE: extrapolated max blur for d >= 4 is 0.75 % of the viewport width (C level). */
        const val MAX_BLUR_WIDTH_FRACTION = 0.0075f

        // ---- phone single column -------------------------------------------------------
        const val PHONE_HEADER_DP = 108f
        const val PHONE_COVER_DP = 76f
        const val PHONE_COVER_CORNER_DP = 12f
        const val PHONE_HEADER_GAP_DP = 14f
        const val PHONE_HORIZONTAL_INSET_DP = 20f
        const val PHONE_CURRENT_FONT_SP = 34f
        const val PHONE_TITLE_SP = 17f
        const val PHONE_ARTIST_SP = 14f
        const val PHONE_TIMES_SP = 12f
        const val PHONE_ANCHOR_FRACTION = 0.40f
        const val PHONE_LYRICS_GAP_DP = 8f
        const val PHONE_PROGRESS_DP = 40f
        const val PHONE_TRANSPORT_DP = 64f
        const val PHONE_BOTTOM_PAD_DP = 12f
        const val PHONE_TRANSPORT_ICON_DP = 26f
        const val PHONE_PLAY_ICON_DP = 32f
        const val PHONE_BAR_HEIGHT_DP = 3f
        const val PHONE_THUMB_DP = 5f
        const val PHONE_LINE_PITCH_RATIO = 284.6f / 100f
        const val PHONE_TRANSLATION_CENTER_RATIO = 116.5f / 100f

        const val MIN_FONT_SCALE_PERCENT = 80
        const val MAX_FONT_SCALE_PERCENT = 140
        const val DEFAULT_FONT_SCALE_PERCENT = 100
    }
}
