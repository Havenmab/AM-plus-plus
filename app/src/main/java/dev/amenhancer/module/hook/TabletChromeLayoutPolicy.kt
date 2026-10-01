package dev.amenhancer.module.hook

import dev.amenhancer.glass.GlassCapsuleBounds
import dev.amenhancer.glass.GlassRepeatMode
import dev.amenhancer.glass.TabletGlassLayoutPolicy
import dev.amenhancer.glass.TopBarGeometry

/**
 * Pure, Android-free decisions for the iPad-style tablet chrome: the TOP navigation capsule and
 * the BOTTOM mini-player capsule.
 *
 * The top capsule is pinned to the window's top centre, so its own placement and the page's
 * reserved top space are one computation. The mini capsule is bottom-centre and mounted at the
 * top of the host's own collapsed player band, exactly like the inherited mini glass.
 * [TopBarGeometry.occupiedTopHeight] stays the single source of truth for the status-bar inset,
 * the reference gap and the host's own `dimen/navigation_tabs_height`, exactly like
 * `GlassPolicy.occupiedHeight` is for the bottom glass. No `android.*` type may appear here, so the
 * host bridge and a JVM regression test share one truth.
 */
internal object TabletChromeLayoutPolicy {

    /** Where the reserved top space is written. */
    enum class TopPaddingTarget { CONTENT_ROOT, SCROLL_CHILD }

    /**
     * Vertical space the top bar owns: the status-bar inset, the reference gap scaled by
     * [density] and the host capsule height taken from `dimen/navigation_tabs_height`.
     */
    fun occupiedTopHeightPx(
        navigationTabsHeightPx: Int,
        topInsetPx: Int,
        density: Float,
        gapDp: Int = TopBarGeometry.TOP_GAP_DP,
    ): Int = TopBarGeometry.occupiedTopHeight(navigationTabsHeightPx, topInsetPx, density, gapDp)

    /**
     * Top margin that seats the capsule under the status bar with the reference gap above it:
     * the occupied height minus the capsule itself. Clamped at zero so a missing host dimension
     * cannot produce a negative offset.
     */
    fun capsuleTopMarginPx(
        navigationTabsHeightPx: Int,
        topInsetPx: Int,
        density: Float,
        gapDp: Int = TopBarGeometry.TOP_GAP_DP,
    ): Int = (occupiedTopHeightPx(navigationTabsHeightPx, topInsetPx, density, gapDp) - navigationTabsHeightPx)
        .coerceAtLeast(0)

    /**
     * Top padding the page viewport reserves so content starts below the capsule. It is the whole
     * occupied height, because the capsule floats above the page inside that interval.
     */
    fun contentTopPaddingPx(
        navigationTabsHeightPx: Int,
        topInsetPx: Int,
        density: Float,
        gapDp: Int = TopBarGeometry.TOP_GAP_DP,
    ): Int = occupiedTopHeightPx(navigationTabsHeightPx, topInsetPx, density, gapDp)

    /**
     * The capsule is fixed to the window and never scrolls with the page, so the reservation is a
     * viewport pad on the content container (contrast the bottom glass, which pads scroll children
     * so the last item can scroll clear of it).
     */
    fun topPaddingTarget(): TopPaddingTarget = TopPaddingTarget.CONTENT_ROOT

    /** True when a window-space touch lands inside the rendered capsule, round ends included. */
    fun capsuleContains(x: Float, y: Float, capsule: GlassCapsuleBounds): Boolean =
        TabletGlassLayoutPolicy.contains(x, y, capsule)

    /** Width of the centred bottom mini-player capsule, delegated to the shared reference geometry. */
    fun miniPlayerWidthPx(screenWidthPx: Int): Int = TopBarGeometry.miniPlayerWidthPx(screenWidthPx)

    /** Left edge that centres [miniPlayerWidthPx] in [screenWidthPx]. */
    fun miniPlayerLeftPx(screenWidthPx: Int): Int = TopBarGeometry.miniPlayerLeftPx(screenWidthPx)

    /**
     * Top margin of the mini capsule inside its mount parent. The capsule takes the top of the
     * host's collapsed player band, exactly like the inherited mini glass, so the host's own peek
     * height — not this policy — decides where that band sits in the window; the margin is zero.
     * Kept as a policy decision so the seat stays data-driven and covered by the pure tests.
     */
    fun miniPlayerTopPx(): Int = 0

    /** True when a window-space touch lands inside the rendered mini capsule, round ends included. */
    fun miniPlayerContains(x: Float, y: Float, capsule: GlassCapsuleBounds): Boolean =
        TabletGlassLayoutPolicy.contains(x, y, capsule)

    /**
     * Host `PlaybackRepeatMode` (`0` off, `1` one, `2` all) mapped to the capsule's three-state
     * enum. An unknown mode falls back to `OFF` rather than throwing in the render path.
     */
    fun repeatModeOf(hostMode: Int): GlassRepeatMode = when (hostMode) {
        1 -> GlassRepeatMode.ONE
        2 -> GlassRepeatMode.ALL
        else -> GlassRepeatMode.OFF
    }
}
