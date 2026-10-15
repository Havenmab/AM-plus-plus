package dev.amenhancer.glass

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

data class GlassTab(
    val id: Int,
    val title: String,
    val icon: Drawable?,
    val enabled: Boolean,
    /**
     * Marks the detachable search destination. Hosts that cannot resolve it leave this
     * false, and the bar keeps every tab inside one capsule.
     */
    val isSearch: Boolean = false,
)

enum class GlassNavigationStyle { Stacked, TabletLabels }

@Composable
fun GlassNavigation(
    tabs: List<GlassTab>,
    selectedId: Int,
    accent: Color,
    foreground: Color,
    backdrop: Backdrop,
    onSelect: (Int) -> Int,
    panelHeight: Dp = GlassPolicy.NAV_HEIGHT_DP.dp,
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    style: GlassNavigationStyle = GlassNavigationStyle.Stacked,
    drawerIcon: Drawable? = null,
    drawerDescription: String = "",
    onDrawer: (() -> Unit)? = null,
    /**
     * AM++ scroll collapse: 0 = one capsule with every tab, 1 = withdrawn capsule + detached
     * search. **Null means this host does not support the collapse at all**, which is the
     * explicit opt-in: the search tab is only ever detached when a host asks for the collapse,
     * so the tablet's top navigation and every other caller keep their full tab strip.
     */
    collapseProgress: (() -> Float)? = null,
    onExpandRequest: (() -> Unit)? = null,
    /**
     * The collapse's rebound amplitudes, as fractions of each spring's own overshoot. Shared by the
     * capsule and the detached search control so the two side buttons cannot animate differently.
     * [collapseReboundScale] is the visible collapse bounce; [expandReboundScale] is the return
     * direction, where each button grows back towards the mini player.
     */
    collapseReboundScale: Float = 1f,
    expandReboundScale: Float = 1f,
) {
    // The reference drag animation normalizes by tabsCount - 1. Keep a one-tab host native.
    if (tabs.size < 2) return
    val index = GlassPolicy.selectedIndex(tabs.map { it.id }, selectedId) ?: return
    val confirmedIndex = rememberUpdatedState(index)
    val confirmedId = rememberUpdatedState(selectedId)
    val select = rememberUpdatedState(onSelect)
    var rejectedSelection by remember { mutableIntStateOf(0) }
    fun request(tab: GlassTab) {
        if (!tab.enabled || select.value(tab.id) != tab.id) rejectedSelection++
    }
    // Only the last tab may detach: the collapsed row keeps the expanded order, so pulling a
    // search tab out of the middle would silently reorder the visible destinations. An
    // unresolved search leaves the row as a single capsule that only shrinks.
    val searchTab = tabs.lastOrNull()?.takeIf {
        collapseProgress != null && it.isSearch && tabs.size > 2
    }
    // The capsule always keeps **every** tab, including search. At progress 0 that is the
    // shipped bar, one capsule of five cells; dropping search here would have silently narrowed
    // the expanded bar to four cells and left search unreachable at rest. By the time the
    // capsule has withdrawn the whole row is faded out, so the cell count no longer has any
    // visual effect — the detach is drawn by the search square fading in at the row's end, not
    // by removing a cell.
    val inCapsule = tabs
    val progress: () -> Float = collapseProgress ?: { 0f }
    // Read once per composition so the gate below cannot disagree with the alpha it mirrors.
    val expandedInteractive = GlassBarCollapsePolicy.expandedInteractive(progress())
    // A menu replacement must discard gestures whose indices refer to the old menu.
    key(tabs.map { it.id }, rejectedSelection) {
        val selection = remember { { confirmedIndex.value } }
        // The bar occupies only the top `panelHeight` of the host island; the remainder is the
        // lift below the capsule. Detaching the search control into its own slot therefore needs
        // this row box, so it can share the capsule's bottom edge instead of the island's.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(panelHeight).align(Alignment.TopStart)) {
                LiquidBottomTabs(
                    selectedTabIndex = selection,
                    // The drag path navigates too, so it takes the same gate as the tap path.
                    onTabSelected = { i ->
                        if (expandedInteractive) inCapsule.getOrNull(i)?.let { if (it.id != confirmedId.value) request(it) }
                    },
                    onSelectedTabClick = { i -> if (expandedInteractive) inCapsule.getOrNull(i)?.let(::request) },
                    isTabEnabled = { i -> inCapsule.getOrNull(i)?.enabled == true },
                    backdrop = backdrop,
                    tabsCount = inCapsule.size,
                    accentOverride = accent,
                    panelHeight = panelHeight,
                    panelBlur = panelBlur,
                    leadingWidth = if (onDrawer == null) 0.dp else panelHeight - 8.dp,
                    leadingContent = onDrawer?.let { open -> {
                        Box(Modifier.width(panelHeight - 8.dp).fillMaxHeight()
                            .semantics { contentDescription = drawerDescription }
                            .clickable(onClick = open), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(24.dp)) {
                                drawerIcon?.let { icon ->
                                    icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                    icon.draw(drawContext.canvas.nativeCanvas)
                                }
                            }
                        }
                    } },
                    collapseProgress = progress,
                    // Always present: the withdrawn capsule is the only way back, so it needs a
                    // face and a tap target whether or not search could be detached.
                    compactContent = { CompactSelectionGlyph(tabs, selectedId, accent, foreground) },
                    onExpandRequest = onExpandRequest,
                    collapseReboundScale = collapseReboundScale,
                    expandReboundScale = expandReboundScale,
                ) {
                    inCapsule.forEach { tab ->
                        LiquidBottomTab(
                            // A faded tab row must stop navigating: `alpha(0f)` hides a subtree
                            // without disabling it, so an invisible cell could otherwise swallow
                            // a tap. The row is inert exactly while its own glyphs are the ones
                            // being hidden, which is also when the compact capsule owns the tap.
                            onClick = { if (expandedInteractive) request(tab) },
                            modifier = Modifier.semantics {
                                selected = tab.id == selectedId
                                contentDescription = tab.title
                            },
                        ) {
                            if (style == GlassNavigationStyle.Stacked || tab.icon != null) Canvas(Modifier.size(24.dp)) {
                                tab.icon?.let { icon ->
                                    val save = drawContext.canvas.nativeCanvas.save()
                                    try {
                                        icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                        icon.draw(drawContext.canvas.nativeCanvas)
                                    } finally { drawContext.canvas.nativeCanvas.restoreToCount(save) }
                                }
                            }
                            if (style == GlassNavigationStyle.Stacked || tab.icon == null)
                                BasicText(tab.title, style = TextStyle(color = foreground,
                                    fontSize = if (style == GlassNavigationStyle.Stacked) 11.sp else 14.sp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (searchTab != null) {
                    DetachedSearchControl(
                        tab = searchTab,
                        selectedId = selectedId,
                        foreground = foreground,
                        accent = accent,
                        backdrop = backdrop,
                        panelBlur = panelBlur,
                        // The row's own height is the control's expanded cell and the box it
                        // bottom-anchors inside, so it comes from the same value the capsule uses.
                        rowHeight = panelHeight,
                        // The cell width needs the same three inputs the capsule's geometry does,
                        // so the control's expanded footprint matches the tab it stands for.
                        tabsCount = inCapsule.size,
                        leadingWidth = if (onDrawer == null) 0.dp else panelHeight - 8.dp,
                        collapseProgress = progress,
                        onSelect = { request(searchTab) },
                        collapseReboundScale = collapseReboundScale,
                        expandReboundScale = expandReboundScale,
                    )
                }
            }
        }
    }
}

/**
 * The search destination, drawn as its own capsule at the row's end edge once the main capsule
 * withdraws. It is the same control the expanded bar already offers as the last tab; only its
 * container changes, so both states select the same destination.
 *
 * The material is deliberately the same call as the main panel: a detached control that used a
 * different glass recipe would read as a foreign element rather than the same bar come apart.
 */
@Composable
private fun BoxScope.DetachedSearchControl(
    tab: GlassTab,
    selectedId: Int,
    foreground: Color,
    accent: Color,
    backdrop: Backdrop,
    panelBlur: Dp,
    rowHeight: Dp,
    tabsCount: Int,
    leadingWidth: Dp,
    collapseProgress: () -> Float,
    onSelect: () -> Unit,
    /**
     * User-set rebound amplitudes, shared with the capsule so the two side buttons cannot disagree.
     * [expandReboundScale] is the return direction, where each button grows back towards the mini
     * player.
     */
    collapseReboundScale: Float = 1f,
    expandReboundScale: Float = 1f,
) {
    val density = LocalDensity.current
    // The raw spring, deliberately unclamped. The capsule and the mini player both ride it
    // through its overshoot; a clamped curve here would be the one element on the row that does
    // not, which is exactly what made this control read as a separate animation.
    val raw = collapseProgress()
    val alpha = GlassBarCollapsePolicy.searchAlpha(raw)
    if (alpha <= 0.001f) return
    val containerColor = if (isSystemInDarkTheme()) Color(0xFF121212).copy(alpha = 0.4f)
        else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val selected = tab.id == selectedId
    BoxWithConstraints(
        Modifier.align(Alignment.BottomEnd).fillMaxWidth().height(rowHeight),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val rowWidthPx = with(density) { maxWidth.toPx() }
        val rowHeightPx = with(density) { rowHeight.toPx() }
        // The drawn circle is the mini player's height, so the three controls on the collapsed row
        // are the same size and share one band.
        val controlPx = with(density) { GlassBarCollapsePolicy.CONTROL_DP.dp.toPx() }
        // The control's expanded footprint is the cell this destination already occupies in the
        // expanded tab row, so the collapsed control is literally that cell narrowing into the
        // circle — the same transformation the capsule performs at the other end of the row. It is
        // deliberately not the whole row width: the capsule's expanded width is the whole row
        // because it holds every tab, whereas this control only ever stood for one cell, and a
        // row-wide box would still be ~95px wide at full opacity and would cover the mini player.
        val cellPx = GlassTabGeometry(rowWidthPx, with(density) { 4f.dp.toPx() },
            with(density) { leadingWidth.toPx() }, tabsCount).tabWidth
        // Both dimensions go through the capsule's own two functions, on the same raw progress, so
        // the control inherits the identical spring, curve and overshoot rather than a lookalike.
        // The width additionally inherits the capsule's *rebound distance*: this control only
        // travels one cell where the capsule travels the whole row, so its own overshoot would be
        // about a fourteenth of the capsule's and would not read as the same animation. Inside the
        // travel the extra term is zero, so the appearance is unchanged.
        //
        // This box is end-aligned, so the control's **end edge is pinned and its start edge travels**.
        // That is what puts the whole rebound on the side facing the mini player: the button reaches
        // back towards it and settles, instead of swelling symmetrically about its own centre.
        val widthPx = GlassBarCollapsePolicy.searchWidth(
            cellPx, controlPx, rowWidthPx, raw,
            collapseReboundScale = collapseReboundScale,
            expandReboundScale = expandReboundScale,
            // The room this control's growing edge has: the gap between it and the mini player.
            maxExpandReboundPx = with(density) { GlassBarCollapsePolicy.GAP_DP.dp.toPx() },
        )
        val heightPx = GlassBarCollapsePolicy.capsuleHeight(rowHeightPx, controlPx, raw)
        // Bottom-anchored on the expanded bar's bottom edge, so the collapsed row's bottom edge is at
        // the same height as the expanded one rather than a few dp above it.
        val dropPx = GlassBarCollapsePolicy.collapseDrop(rowHeightPx, controlPx, raw)
        Box(
            Modifier
                .width(with(density) { widthPx.toDp() })
                .height(with(density) { heightPx.toDp() })
                .alpha(alpha)
                .graphicsLayer { translationY = dropPx }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(panelBlur.toPx())
                        val edge = minOf(24f.dp.toPx(), size.minDimension * 0.375f)
                        lens(edge, edge)
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                // The press feedback is clipped to the same capsule the glass draws, so this
                // button and the withdrawn capsule at the other end of the row give **identical**
                // press feedback. They are both square layout nodes, so an unclipped indication
                // reads as a square behind a round button on either of them.
                .clip(Capsule())
                // Only interactive once it is actually on screen: below that the bar is still the
                // expanded tab strip, where the same destination is reachable as the last cell.
                .then(
                    if (GlassBarCollapsePolicy.searchInteractive(raw)) {
                        Modifier
                            .clickable(role = Role.Button, onClick = onSelect)
                            .semantics { contentDescription = tab.title; this.selected = selected }
                    } else Modifier.clearAndSetSemantics {}
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The search button is a **destination**, so it carries the accent exactly like the
            // capsule's compact face does — when search is the selected tab this control IS the
            // selection, and leaving it uncoloured made the collapsed row look like nothing was
            // selected while the expanded row had clearly shown red.
            //
            // The tint is applied to a **copy** of the drawable, for the same reason the compact
            // face copies: `tab.icon` is the very drawable the expanded tab row paints, so tinting
            // it in place would repaint that tab for the rest of the session and the red would
            // follow the tab through every later selection. `remember`ed against the tint so the
            // per-frame collapse animation does not allocate one per frame.
            val resources = LocalContext.current.resources
            val icon = remember(tab.icon, selected, accent) {
                val source = tab.icon
                if (!selected) source
                else {
                    val copy = source?.constantState?.newDrawable(resources)?.mutate()
                    copy?.setTint(accent.toArgb())
                    copy ?: source
                }
            }
            Canvas(Modifier.size(24.dp)) {
                icon?.let {
                    val save = drawContext.canvas.nativeCanvas.save()
                    try {
                        it.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        it.draw(drawContext.canvas.nativeCanvas)
                    } finally { drawContext.canvas.nativeCanvas.restoreToCount(save) }
                }
            }
        }
    }
}

/**
 * The withdrawn capsule's face: the selected tab's glyph, so the user can still tell where they
 * are. A selected search tab falls back to the first tab's glyph and drops the accent, because
 * search has already left the capsule and the red accent means "the capsule's own selection".
 *
 * The glyph is **copied** before it is tinted. `GlassTab.icon` is the very drawable the expanded
 * tab row paints, so tinting it in place would repaint that tab for the rest of the session: the
 * accent would survive the bar expanding again and follow the tab through every later selection,
 * because a `Drawable`'s tint belongs to the drawable and not to this composition. The copy is
 * remembered against the tint so the per-frame collapse animation does not allocate one per frame.
 */
@Composable
private fun CompactSelectionGlyph(
    tabs: List<GlassTab>,
    selectedId: Int,
    accent: Color,
    foreground: Color,
) {
    val selected = tabs.firstOrNull { it.id == selectedId }
    val searchLeft = selected?.isSearch == true
    val glyph = if (searchLeft) tabs.firstOrNull() else selected
    val tint = if (searchLeft) foreground else accent
    val resources = LocalContext.current.resources
    val icon = remember(glyph?.icon, tint) {
        val source = glyph?.icon
        // Only a copy is ever tinted. If the drawable exposes no constant state there is nothing
        // safe to tint, so the glyph is drawn untinted rather than recolouring the shared one.
        val copy = source?.constantState?.newDrawable(resources)?.mutate()
        copy?.setTint(tint.toArgb())
        copy ?: source
    }
    Canvas(Modifier.size(24.dp)) {
        icon?.let {
            val save = drawContext.canvas.nativeCanvas.save()
            try {
                it.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                it.draw(drawContext.canvas.nativeCanvas)
            } finally { drawContext.canvas.nativeCanvas.restoreToCount(save) }
        }
    }
}
