package dev.amenhancer.glass

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class GlassTab(val id: Int, val title: String, val icon: Drawable?, val enabled: Boolean)

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
    // A menu replacement must discard gestures whose indices refer to the old menu.
    key(tabs.map { it.id }, rejectedSelection) {
        val selection = remember { { confirmedIndex.value } }
        // The tablet chrome draws its own capsule over the host's top tab bar, which the
        // reference shows noticeably more compact than the anchor it replaces. Only the
        // TabletLabels style is trimmed; Stacked keeps the anchor's full geometry. The anchor
        // height varies per device, so the height is a fraction of it rounded to whole dp.
        val tabletBar = style == GlassNavigationStyle.TabletLabels
        val barHeight = if (tabletBar) {
            (panelHeight.value * GlassPolicy.TABLET_NAV_HEIGHT_FRACTION).roundToInt().dp
        } else panelHeight
        // The tablet chrome overlays the host's top tab bar, whose own labels are slightly larger
        // and heavier than the phone bottom bar's; the two styles therefore get different label
        // typography, and the tablet bar sizes each cell to the label it is about to draw. The
        // reference's labels are heavier still, hence SemiBold on the tablet bar only.
        val labelStyle = if (tabletBar) {
            TextStyle(color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            TextStyle(color = foreground, fontSize = 11.sp)
        }
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        // Tablet cells hug their content: a label tab is as wide as its text, an icon tab as wide
        // as its 24dp glyph. The phone bar passes null and keeps upstream's equal shares.
        val tabContentWidths = if (tabletBar) {
            remember(tabs, textMeasurer, density, labelStyle) {
                tabs.map { tab ->
                    if (tab.icon != null) 24.dp
                    else with(density) {
                        textMeasurer.measure(AnnotatedString(tab.title), labelStyle, maxLines = 1)
                            .size.width.toDp()
                    }
                }
            }
        } else null
        // An icon-only tab is exactly the tab that draws its icon and no label above, i.e. the one
        // with a non-null icon; that flag is the caller's own data rather than a width heuristic.
        val tabIconOnly = if (tabletBar) remember(tabs) { tabs.map { it.icon != null } } else null
        val drawerWidth = if (onDrawer == null) 0.dp else barHeight - 8.dp
        // The capsule is drawn at its content-derived width - the leading action, every padded
        // cell and the row-inset on both sides - while the layout node still reports the anchor's
        // full width. Unusable or oversized content clamps to the anchor and falls back to the
        // full-width geometry, so nothing overflows.
        val drawnWidthPx = if (tabletBar && tabContentWidths != null) {
            remember(tabContentWidths, tabIconOnly, drawerWidth, density) {
                with(density) {
                    var total = drawerWidth.toPx() + 2f * GlassPolicy.TAB_ROW_INSET_DP.dp.toPx()
                    tabContentWidths.forEachIndexed { index, width ->
                        val padding = if (tabIconOnly?.get(index) == true) {
                            GlassPolicy.TABLET_ICON_TAB_PADDING_DP
                        } else {
                            GlassPolicy.TABLET_TAB_PADDING_DP
                        }
                        total += width.toPx() + 2f * padding.dp.toPx()
                    }
                    total
                }
            }
        } else 0f
        LiquidBottomTabs(
            selectedTabIndex = selection,
            onTabSelected = { i -> tabs.getOrNull(i)?.let { if (it.id != confirmedId.value) request(it) } },
            onSelectedTabClick = { i -> tabs.getOrNull(i)?.let(::request) },
            isTabEnabled = { i -> tabs.getOrNull(i)?.enabled == true },
            backdrop = backdrop,
            tabsCount = tabs.size,
            modifier = if (tabletBar) {
                Modifier.insetToDrawnWidth(drawnWidthPx, GlassPolicy.TABLET_NAV_WIDTH_FRACTION)
            } else Modifier,
            accentOverride = accent,
            panelHeight = barHeight,
            panelBlur = panelBlur,
            leadingWidth = drawerWidth,
            leadingContent = onDrawer?.let { open -> {
                Box(Modifier.width(drawerWidth).fillMaxHeight()
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
            tabContentWidths = tabContentWidths,
            tabIconOnly = tabIconOnly,
        ) { index, weight ->
            val tab = tabs[index]
            LiquidBottomTab(
                onClick = { request(tab) },
                weight = weight,
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
                if (style == GlassNavigationStyle.Stacked || tab.icon == null) {
                    BasicText(tab.title, style = labelStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Draws the bar at an explicit [drawnWidthPx], centred, while the layout node itself keeps
 * reporting the full width it was offered. [drawnWidthPx] is clamped to [maxWidthFraction] of that
 * width, so content that does not fit stays at the caller's full-width ceiling instead of
 * overflowing.
 *
 * The tablet session's layout contract requires the measured Compose content width to equal the
 * glass host view width (`FragmentTabletGlassPolicy.navigationLayoutReady`), so the narrowing has
 * to happen inside the node rather than by sizing the node: a plain `width(...)` would leave the
 * session's native navigation visible forever. The library computes its tab and touch geometry
 * from the constraints it receives, so the content-hugging capsule and its gestures stay
 * consistent with the narrower content box.
 */
private fun Modifier.insetToDrawnWidth(drawnWidthPx: Float, maxWidthFraction: Float = 1f): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val ceiling = (constraints.maxWidth * maxWidthFraction).roundToInt()
                .coerceIn(0, constraints.maxWidth)
            // Round the content-derived width up so the dp -> px -> Int round trip cannot leave the
            // measured cells a sub-pixel wider than the box and trip the fallback.
            val width = if (drawnWidthPx.isFinite()) {
                ceil(drawnWidthPx).toInt().coerceIn(0, ceiling)
            } else ceiling
            val inset = (constraints.maxWidth - width) / 2
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(inset, 0) }
        }
    }
