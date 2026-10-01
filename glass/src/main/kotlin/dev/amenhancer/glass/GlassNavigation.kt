package dev.amenhancer.glass

import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.toArgb
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

/**
 * What a [GlassTab] paints. The shared tab capsule is used by both the phone bottom bar and the
 * tablet top bar, so the display mode is per tab rather than per bar.
 */
enum class GlassTabDisplay { ICON_AND_TEXT, TEXT, ICON }

/**
 * One tab of the shared liquid-glass capsule.
 *
 * [display] defaults to [GlassTabDisplay.ICON_AND_TEXT], which is the layout the phone bottom bar
 * has always rendered: a 24.dp icon canvas above an 11.sp title. [GlassTabDisplay.TEXT] drops the
 * icon canvas entirely (no blank icon box is reserved) and [GlassTabDisplay.ICON] drops the title
 * line, so icon-only cells are not padded out by an empty label.
 */
data class GlassTab(
    val id: Int,
    val title: String,
    val icon: Drawable?,
    val enabled: Boolean,
    val display: GlassTabDisplay = GlassTabDisplay.ICON_AND_TEXT,
)

/** The 24.dp icon canvas and 11.sp label of the original phone tab, shared by every display mode. */
private val TabIconSize = 24.dp
private const val TAB_LABEL_SIZE_SP = 11f

/**
 * The shared iPad-style navigation capsule, on top or at the bottom.
 *
 * The material, the sliding translucent selection mask, the press/"灵动" squeeze animation and the
 * free thumb drag all come from the library's [LiquidBottomTabs]; this composable only decides what
 * each cell paints. The selected cell paints its title and icon with [accent] (the host accent, as
 * in the reference) and every other cell keeps [foreground].
 *
 * Selection is hoisted: [onSelect] returns the id the host accepted, and only a tap whose return
 * value equals the tab's id may move the highlight.
 */
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
        LiquidBottomTabs(
            selectedTabIndex = selection,
            onTabSelected = { i -> tabs.getOrNull(i)?.let { if (it.id != confirmedId.value) request(it) } },
            onSelectedTabClick = { i -> tabs.getOrNull(i)?.let(::request) },
            isTabEnabled = { i -> tabs.getOrNull(i)?.enabled == true },
            backdrop = backdrop,
            tabsCount = tabs.size,
            accentOverride = accent,
            panelHeight = panelHeight,
            panelBlur = panelBlur,
        ) {
            tabs.forEach { tab ->
                // Keep each cell's remembered tinted drawable keyed to the tab, so a menu swap can
                // never hand a cell the previous menu's clone.
                key(tab.id) {
                    val tint = if (tab.id == selectedId) accent else foreground
                    LiquidBottomTab(
                        onClick = { request(tab) },
                        modifier = Modifier.semantics {
                            selected = tab.id == selectedId
                            contentDescription = tab.title
                        },
                    ) {
                        when (tab.display) {
                            GlassTabDisplay.ICON_AND_TEXT -> {
                                TabIcon(tab.icon, tint)
                                TabLabel(tab.title, tint)
                            }

                            GlassTabDisplay.TEXT -> TabLabel(tab.title, tint)
                            GlassTabDisplay.ICON -> TabIcon(tab.icon, tint)
                        }
                    }
                }
            }
        }
    }
}

/** The 24.dp icon canvas. A null icon draws nothing and reserves no extra space. */
@Composable
private fun TabIcon(icon: Drawable?, tint: Color) {
    // Drawable exposes no public tint getter, so never mutate the host's icon: draw a tinted clone.
    val tinted = remember(icon, tint) { tintedDrawable(icon, tint) }
    Canvas(Modifier.size(TabIconSize)) {
        tinted?.let { draw ->
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            try {
                draw.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                draw.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }
}

/** The 11.sp one-line title, tinted with the cell's colour. */
@Composable
private fun TabLabel(title: String, tint: Color) {
    val style = remember(tint) { TextStyle(color = tint, fontSize = TAB_LABEL_SIZE_SP.sp) }
    BasicText(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A tinted copy of a host drawable.
 *
 * [Drawable] exposes no public tint getter, so the caller's instance is never mutated: clone it
 * through its constant state and tint the clone. A drawable that exposes no constant state cannot
 * be cloned at all, so it is skipped (draws nothing) rather than tinted or re-bounded in place.
 */
internal fun tintedDrawable(icon: Drawable?, tint: Color): Drawable? {
    if (icon == null) return null
    val clone = icon.constantState?.newDrawable()?.mutate() ?: return null
    clone.setTint(tint.toArgb())
    clone.setTintMode(PorterDuff.Mode.SRC_IN)
    return clone
}
