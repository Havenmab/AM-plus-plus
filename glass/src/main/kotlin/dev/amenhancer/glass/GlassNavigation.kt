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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.Capsule
import dev.amenhancer.backdrop.VibrantBlur
import kotlin.math.roundToInt

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
     * search. **Null means this host does not support the collapse at all**, so the search tab is
     * only ever detached for hosts that opt in.
     */
    collapseProgress: (() -> Float)? = null,
    onExpandRequest: (() -> Unit)? = null,
    /**
     * Rebound amplitudes as fractions of each spring's own overshoot, shared by the capsule and the
     * detached search control. [expandReboundScale] is the return direction, towards the mini.
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
    // Only the last tab may detach: the collapsed row keeps the expanded order, so pulling a search
    // tab out of the middle would reorder the visible destinations.
    val searchTab = tabs.lastOrNull()?.takeIf {
        collapseProgress != null && it.isSearch && tabs.size > 2
    }
    // The capsule keeps **every** tab, search included: dropping it would narrow the *expanded* bar,
    // where the tab is the way to reach search. Collapsed, the row has faded out anyway, so the
    // detach is the search square fading in at the row's end.
    val inCapsule = tabs
    val progress: () -> Float = collapseProgress ?: { 0f }
    val liveProgress = rememberUpdatedState(progress)
    val expandedInteractive by remember {
        derivedStateOf { GlassBarCollapsePolicy.expandedInteractive(liveProgress.value()) }
    }
    // A menu replacement must discard gestures whose indices refer to the old menu.
    key(tabs.map { it.id }, rejectedSelection) {
        val selection = remember { { confirmedIndex.value } }
        // The bar occupies only the top `panelHeight` of the host island, so the detached control
        // needs this row box to share the capsule's bottom edge instead of the island's.
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
                    optimizeCollapseRendering = collapseProgress != null,
                    // Always present: the withdrawn capsule is the only way back, so it needs a
                    // face and a tap target whether or not search could be detached.
                    compactContent = { CompactSelectionGlyph(tabs, selectedId, accent, foreground) },
                    onExpandRequest = onExpandRequest,
                    collapseReboundScale = collapseReboundScale,
                    expandReboundScale = expandReboundScale,
                ) {
                    inCapsule.forEach { tab ->
                        LiquidBottomTab(
                            shape = GlassCollapseShapes.capsule(collapseProgress != null),
                            // A faded tab row must stop navigating: `alpha(0f)` hides a subtree
                            // without disabling it, so an invisible cell could absorb a tap.
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
 * withdraws. It is the control the expanded bar already offers as the last tab, so both states
 * select the same destination, and it uses the same glass recipe as the main panel.
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
    /** User-set rebound amplitudes, shared with the capsule. See [GlassNavigation]. */
    collapseReboundScale: Float = 1f,
    expandReboundScale: Float = 1f,
) {
    val density = LocalDensity.current
    val liveProgress = rememberUpdatedState(collapseProgress)
    val searchAlpha = remember {
        derivedStateOf { GlassBarCollapsePolicy.searchAlpha(liveProgress.value()) }
    }
    val interactive by remember {
        derivedStateOf { GlassBarCollapsePolicy.searchInteractive(liveProgress.value()) }
    }
    val reserveLayer by remember {
        derivedStateOf {
            val progress = liveProgress.value()
            progress != 0f && progress != 1f
        }
    }
    val radiusPx = with(density) { panelBlur.toPx() }
    val material = remember(radiusPx) { VibrantBlur(radiusPx) }
    val containerColor = if (isSystemInDarkTheme()) Color(0xFF121212).copy(alpha = 0.4f)
        else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val selected = tab.id == selectedId
    BoxWithConstraints(
        Modifier.align(Alignment.BottomEnd).fillMaxWidth().height(rowHeight),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val rowWidthPx = with(density) { maxWidth.toPx() }
        val rowHeightPx = with(density) { rowHeight.toPx() }
        val leadingPx = with(density) { leadingWidth.toPx() }
        val expandedCell = GlassTabGeometry(rowWidthPx, 4f * density.density, leadingPx, tabsCount).tabWidth
        val minLayerSize = if (reserveLayer) IntSize(
            (maxOf(expandedCell, GlassBarCollapsePolicy.CONTROL_DP * density.density) +
                GlassBarCollapsePolicy.GAP_DP * density.density).roundToInt(),
            rowHeightPx.roundToInt(),
        ) else IntSize.Zero
        val materialModifier = remember(backdrop, material, containerColor, minLayerSize) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                minLayerSize = material.paddedSize(minLayerSize),
                clipCompositingStrategy = CompositingStrategy.Auto,
                shape = { GlassCollapseShapes.capsule(true) },
                effects = {
                    material.applyTo(this)
                    val edge = minOf(24f.dp.toPx(), size.minDimension * 0.375f)
                    lens(edge, edge)
                },
                onDrawSurface = { drawRect(containerColor) },
            )
        }
        val controls = remember(rowWidthPx, rowHeightPx, density, tabsCount, leadingPx,
            collapseReboundScale, expandReboundScale) {
            derivedStateOf {
                GlassBarCollapseLayout.controls(
                    rowWidthPx = rowWidthPx,
                    rowHeightPx = rowHeightPx,
                    density = density.density,
                    tabsCount = tabsCount,
                    progress = liveProgress.value(),
                    collapseReboundScale = collapseReboundScale,
                    expandReboundScale = expandReboundScale,
                    leadingWidthPx = leadingPx,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                // The native hit test uses this same geometry. Read the spring in measurement,
                // keeping the drawable/icon composition and its material stable between frames.
                .layout { measurable, constraints ->
                    if (searchAlpha.value <= 0.001f) {
                        layout(0, 0) {}
                    } else {
                        val bounds = controls.value
                        val width = constraints.constrainWidth(bounds.searchWidth.roundToInt())
                        val height = constraints.constrainHeight(bounds.height.roundToInt())
                        val child = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { child.placeRelative(0, 0) }
                    }
                }
                .graphicsLayer {
                    alpha = searchAlpha.value
                    translationY = if (alpha > 0.001f) controls.value.top else 0f
                }
                .then(materialModifier)
                // Clipped to the capsule the glass draws: the layout node is square, so an
                // unclipped indication would show a square behind a round button.
                .clip(GlassCollapseShapes.capsule(true))
                // Interactive only once it is on screen; below that the expanded tab strip holds
                // the same destination.
                .then(
                    if (interactive) {
                        Modifier
                            .clickable(role = Role.Button, onClick = onSelect)
                            .semantics { contentDescription = tab.title; this.selected = selected }
                    } else Modifier.clearAndSetSemantics {}
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Search is a destination, so it carries the accent like the capsule's compact face.
            // The tint goes on a **copy**: `tab.icon` is the drawable the expanded tab row paints,
            // and tinting it in place would repaint that tab for the rest of the session.
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
 * search has left the capsule. The glyph is **copied** before it is tinted, since a `Drawable`'s
 * tint belongs to the drawable and would repaint the expanded tab row.
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
        // No constant state means nothing safe to copy, so the glyph is drawn untinted.
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
