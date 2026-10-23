package dev.amenhancer.lyricsplayer

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * User-facing settings of the page (settings spec §8). The host returns the current values
 * from [LyricsPlayerPort.preferences]; every number is clamped again inside the pure
 * strategy classes, so an out-of-range value can never break the page.
 *
 * RE: values come from spec §6 until rev-eng publishes constants.json.
 */
data class LyricsPlayerConfig(
    /** Line-change / scroll speed multiplier in percent (40–160, 100 = Apple timing). */
    val motionPercent: Int = 100,
    /** Per-line stagger in milliseconds (0–120, 50 = Apple timing). */
    val staggerMs: Int = 50,
    /** Emphasis scale of the current line in percent (100–114). */
    val emphasisPercent: Int = 114,
    /** Type size multiplier in percent (80–140). */
    val fontScalePercent: Int = 100,
)

/**
 * Page chrome metrics in dp/sp. Kept in one place (and out of the pure strategy classes,
 * which must stay Android-free) so a rev-eng delta only touches this table.
 *
 * RE: every value is a first-pass reading of spec §5.1; expect a delta from rev-eng.
 */
object LyricsPlayerChromeSpec {
    val TOP_BAR_HEIGHT: Dp = 56.dp
    val TOP_BAR_HORIZONTAL_PADDING: Dp = 8.dp
    val TOP_BAR_BUTTON: Dp = 48.dp
    val TOP_BAR_ICON: Dp = 22.dp
    val TITLE_SIZE = 15.sp
    val SUBTITLE_SIZE = 11.sp

    /** Touch target of the progress bar; all controls keep the 48 dp minimum (spec §5.1). */
    val PROGRESS_TOUCH_HEIGHT: Dp = 48.dp
    val PROGRESS_HORIZONTAL_INSET: Dp = 20.dp
    val PROGRESS_TRACK_HEIGHT: Dp = 3.dp
    val PROGRESS_THUMB_RADIUS: Dp = 6.dp
    val PROGRESS_THUMB_RING: Dp = 1.5.dp
    val TIME_SIZE = 11.sp

    val TRANSPORT_HEIGHT: Dp = 68.dp
    val TRANSPORT_BUTTON: Dp = 48.dp
    val TRANSPORT_ICON: Dp = 26.dp
    val TRANSPORT_GAP: Dp = 24.dp
    val BOTTOM_PADDING: Dp = 10.dp

    val EMPTY_TEXT_SIZE = 17.sp

    /** Gaussian radius of the blurred artwork behind the page. */
    val BACKGROUND_BLUR: Dp = 80.dp

    /** The artwork is scaled up before blurring so no transparent edge shows. */
    const val BACKGROUND_SCALE = 1.18f

    /** Old devices (API < 31) cannot blur; a 1/16 downscale is the fallback. */
    const val BACKGROUND_LEGACY_DOWNSCALE = 16

    /** Dark scrim over the blurred cover, top to bottom (ARGB). */
    val SCRIM_COLORS = listOf(
        0.00f to 0x99000000.toInt(),
        0.45f to 0xCC000000.toInt(),
        1.00f to 0xF2000000.toInt(),
    )
}
