package dev.amenhancer.glass

import kotlin.math.min

/**
 * Geometry policy for the tablet-only iPad-style chrome: the TOP navigation capsule and the
 * centred BOTTOM mini-player capsule.
 *
 * Pure policy, in the spirit of [TabletGlassLayoutPolicy]: no `android.*` types, so the host
 * bridge and the regression tests share one source of truth. Every value here is an iPad
 * reference proportion, never a host dimension — the host's own `dimen/navigation_tabs_height`
 * stays the capsule height source of truth and is always passed in.
 */
object TopBarGeometry {
    /**
     * Gap between the status-bar inset and the top of the capsule. From the iPad reference
     * proportions, not from a host dimension.
     */
    const val TOP_GAP_DP = 8

    /**
     * Smallest margin the centred mini capsule keeps on each side of the screen, in the same
     * pixel unit the host passes in. From the iPad reference proportions, not from a host
     * dimension.
     */
    const val MIN_SIDE_MARGIN_PX = 16

    /**
     * Vertical space the top bar consumes: the status-bar inset, the reference gap scaled by
     * [density] and truncated (matching [GlassPolicy.occupiedHeight]), plus the capsule height the
     * host measured from its own `navigation_tabs_height`.
     */
    fun occupiedTopHeight(
        navigationTabsHeightPx: Int,
        topInsetPx: Int,
        density: Float,
        gapDp: Int = TOP_GAP_DP,
    ): Int = topInsetPx + (gapDp * density).toInt() + navigationTabsHeightPx

    /** The capsule's round end radius: half its height. */
    fun capsuleCornerPx(heightPx: Int): Float = heightPx / 2f

    /**
     * Width of the centred mini capsule: roughly half the screen, but never wider than the screen
     * minus one [MIN_SIDE_MARGIN_PX] margin on each side. Clamped at zero so narrow widths stay
     * well-defined instead of going negative.
     */
    fun miniPlayerWidthPx(screenWidthPx: Int): Int {
        val available = screenWidthPx - 2 * MIN_SIDE_MARGIN_PX
        return min(screenWidthPx / 2, available).coerceAtLeast(0)
    }

    /** Left edge that centres [miniPlayerWidthPx] in [screenWidthPx]. */
    fun miniPlayerLeftPx(screenWidthPx: Int): Int =
        (screenWidthPx - miniPlayerWidthPx(screenWidthPx)) / 2
}
