package dev.amenhancer.module.hook

import dev.amenhancer.glass.GlassCapsuleBounds
import dev.amenhancer.glass.GlassRepeatMode
import dev.amenhancer.glass.TabletGlassLayoutPolicy
import dev.amenhancer.glass.TopBarGeometry

/**
 * Pure, Android-free decisions for the iPad-style tablet chrome: the TOP navigation capsule and
 * the BOTTOM mini-player capsule.
 *
 * The top capsule floats over the page content (`navigation_host_group`), exactly like the phone
 * glass floats over the scene, so there is **no** reserved top padding: the reference chrome has no
 * blank strip and the page keeps scrolling under the glass. [TopBarGeometry] stays the single
 * source of truth for the status-bar inset, the reference gap and the host's own
 * `dimen/navigation_tabs_height`, exactly like `GlassPolicy.occupiedHeight` is for the bottom
 * glass. No `android.*` type may appear here, so the host bridge and a JVM regression test share
 * one truth.
 */
internal object TabletChromeLayoutPolicy {

    /** Side gap the floating top capsule keeps from the window edges, in whole dp. */
    const val TOP_BAR_SIDE_GAP_DP = 16

    /**
     * Width one top-nav tab reserves inside the floating capsule, in whole dp. The iPad reference
     * top bar hugs its tabs; the shared component fills whatever slot it is given, so the host sizes
     * the centred slot here instead. This is the single tuning knob for the top capsule's width.
     *
     * Derived from the reference: there the whole capsule spans roughly 22.5% of the window with
     * five cells plus the (omitted) sidebar toggle, i.e. about 4.5% per cell — ~58dp on a 1280dp
     * tablet. 60dp keeps that proportion with a little breathing room for a three-character CJK
     * label at the shared 11sp tab size.
     */
    const val TOP_TAB_CELL_DP = 60

    /** Sheet progress at which the top capsule has completely faded for the expanded player. */
    const val EXPAND_FADE_END = 0.35f

    /** Sheet progress band over which the collapsed mini capsule hands over to the full player. */
    const val MINI_FADE_START = 0.35f
    const val MINI_FADE_END = 0.6f

    /**
     * Vertical space the top bar occupies: the status-bar inset, the reference gap scaled by
     * [density] and the host capsule height taken from `dimen/navigation_tabs_height`. Kept for
     * documentation of the occupied band even though the page no longer reserves it.
     */
    fun occupiedTopHeightPx(
        navigationTabsHeightPx: Int,
        topInsetPx: Int,
        density: Float,
        gapDp: Int = TopBarGeometry.TOP_GAP_DP,
    ): Int = TopBarGeometry.occupiedTopHeight(navigationTabsHeightPx, topInsetPx, density, gapDp)

    /**
     * Top margin that seats the floating capsule under the status bar with the reference gap above
     * it: the occupied height minus the capsule itself. Clamped at zero so a missing host dimension
     * cannot produce a negative offset.
     */
    fun capsuleTopMarginPx(
        navigationTabsHeightPx: Int,
        topInsetPx: Int,
        density: Float,
        gapDp: Int = TopBarGeometry.TOP_GAP_DP,
    ): Int = (occupiedTopHeightPx(navigationTabsHeightPx, topInsetPx, density, gapDp) - navigationTabsHeightPx)
        .coerceAtLeast(0)

    /** Side margin that centres the top capsule with equal whitespace on both window edges. */
    fun topBarSideMarginPx(density: Float, gapDp: Int = TOP_BAR_SIDE_GAP_DP): Int =
        (gapDp * density).toInt().coerceAtLeast(0)

    /**
     * Width of the centred top capsule: one [TOP_TAB_CELL_DP] slot per tab, never wider than the
     * window minus one [topBarSideMarginPx] on each side. At least a two-tab slot while the host
     * menu has not been read yet, and clamped at zero for degenerate widths.
     */
    fun topBarWidthPx(screenWidthPx: Int, tabsCount: Int, density: Float): Int {
        val available = (screenWidthPx - 2 * topBarSideMarginPx(density)).coerceAtLeast(0)
        val desired = ((tabsCount.coerceAtLeast(2) * TOP_TAB_CELL_DP) * density).toInt()
        return desired.coerceAtMost(available).coerceAtLeast(0)
    }

    /**
     * How far the top capsule has faded because the player sheet is expanding: `0` while collapsed
     * (the capsule is fully opaque), `1` once [progress] passes [EXPAND_FADE_END] (the capsule is
     * hidden). Smooth-stepped so the fade has no visible knee, and allocation-free for the
     * pre-draw path.
     */
    fun expandHideFactor(progress: Float): Float = smoothStep(progress, 0f, EXPAND_FADE_END)

    /**
     * Alpha of the collapsed mini capsule as the sheet expands. It stays opaque through the
     * collapsed band and hands over to the full player across the same curve the inherited
     * author glass uses (`updateTransition`), so the replacement seat disappears in step with the
     * surface it stands in for.
     */
    fun miniPlayerAlpha(progress: Float): Float =
        if (progress <= 0.001f) 1f else 1f - smoothStep(progress, MINI_FADE_START, MINI_FADE_END)

    /** True when a window-space touch lands inside the rendered capsule, round ends included. */
    fun capsuleContains(x: Float, y: Float, capsule: GlassCapsuleBounds): Boolean =
        TabletGlassLayoutPolicy.contains(x, y, capsule)

    /** Width of the centred bottom mini-player capsule, delegated to the shared reference geometry. */
    fun miniPlayerWidthPx(screenWidthPx: Int): Int = TopBarGeometry.miniPlayerWidthPx(screenWidthPx)

    /** Left edge that centres [miniPlayerWidthPx] in [screenWidthPx]. */
    fun miniPlayerLeftPx(screenWidthPx: Int): Int = TopBarGeometry.miniPlayerLeftPx(screenWidthPx)

    /**
     * Documented seat knob for the collapsed mini capsule. The host's own peek height decides
     * where the collapsed band sits in the window, so this stays zero; the real seat is the
     * author's native mini offset inside `player_sheet_container` (see [miniPlayerSeatPx]).
     * Tune a residual offset here rather than in the session.
     */
    fun miniPlayerTopPx(): Int = 0

    /**
     * Seat of the mini capsule inside `player_sheet_container` when collapsed. The author's own
     * mini glass is mounted at `Gravity.TOP` of the sheet and seated at the native
     * `mini_player` root's offset within that sheet, so a collapse lands exactly where the native
     * band sits instead of at the sheet's raw top edge. [nativeOffsetInSheetPx] is that measured
     * offset; [miniPlayerTopPx] stays the documented fine-tune on top of it. Never negative, so a
     * not-yet-laid-out source cannot push the capsule off the sheet.
     */
    fun miniPlayerSeatPx(nativeOffsetInSheetPx: Int): Int =
        (nativeOffsetInSheetPx + miniPlayerTopPx()).coerceAtLeast(0)

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

    /** Hermite smooth-step of [value] across `[start, end]`, clamped to `[0, 1]`. */
    private fun smoothStep(value: Float, start: Float, end: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
