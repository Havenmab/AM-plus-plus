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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
 * each cell paints. Cells keep [foreground] unless the caller opts into [tintSelectedWithAccent]
 * (the shipped sessions do not, and the iPad top bar no longer does either: the library's own
 * recorded-row accent tint is what colours the cell the droplet covers). The iPad top bar keeps the
 * library's own cell-layer recording — the reference's refraction source, so the droplet refracts
 * the labels themselves — and opts into a bigger/heavier [tabLabelSize]/[tabLabelWeight] and a thin
 * [panelHeight]. It deliberately leaves [effectReferenceHeight] and [refractionScalesWithThumb] at
 * their defaults: both deviations moved the refracted copy away from the crisp label, so the
 * library's own absolute-dp effect path is what the tablet uses; every parameter defaults to the
 * shipped phone/dual-pane value.
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
    /**
     * The panel height the library's absolute-dp lens/squeeze constants were authored for.
     *
     * The vendored component hardcodes its refraction and press constants in absolute dp, tuned
     * against the reference bar's height (the shipped phone bar is 56dp), so a thinner capsule makes
     * them look oversized. Defaulted to [panelHeight], i.e. a scale of exactly 1 — the shipped
     * phone/dual-pane bar is unchanged; the thin iPad top bar passes the reference height it wants
     * to keep the proportions of.
     */
    effectReferenceHeight: Dp = panelHeight,
    /**
     * Size of each cell's title, in sp. Defaults to the shared phone tab label's 11sp so the
     * shipped phone/dual-pane bar is byte-identical; the iPad top bar opts into a bigger size
     * because the user found the top-bar text 「太小」.
     */
    tabLabelSize: TextUnit = TAB_LABEL_SIZE_SP.sp,
    /**
     * Weight of each cell's title. Defaults to `null`, which is `TextStyle`'s own default (normal)
     * and therefore exactly the phone bar's current rendering; the iPad top bar opts into a heavier
     * face because the user found its text 「太细」.
     */
    tabLabelWeight: FontWeight? = null,
    /**
     * Paints the selected cell's title and icon with [accent] instead of [foreground].
     *
     * Off by default so the shipped phone/dual-pane bar keeps its exact rendering: there the
     * selection is conveyed by the library's translucent thumb alone, and the host already
     * pre-tints its icons to the same foreground. The tablet iPad top bar opts in, because its
     * reference turns the selected label and the search glyph the host accent colour.
     */
    tintSelectedWithAccent: Boolean = false,
    /**
     * Keeps the mask's glass while dropping the ghost of the selected label.
     *
     * The library's capsule draws its cells twice: once visibly and once — invisible but recorded —
     * into the layer the sliding thumb samples. The thumb's lens then displaces, colour-fringes and
     * press-scales that recorded copy, so a pressed selection mask shows the selected label/icon
     * duplicated and smeared inside itself. This switch stops the cells from ever being recorded: the
     * recorded layer keeps only the panel's own page material — the content behind the bar plus the
     * container tint, unblurred so the lens still has edges to displace and colour-fringe — while the
     * one visible copy of the cells is drawn on top of the mask.
     *
     * The mask itself, the press/"灵动" squeeze and the free thumb drag are unchanged. Off by
     * default, so the shipped phone/dual-pane bar keeps the reference layer and animation exactly.
     *
     * The iPad top bar deliberately does **not** use this: its reference refracts the labels
     * themselves, and the library's own recording is what the droplet's lens samples. This switch
     * remains for a caller that prefers to refract the page material instead.
     */
    cleanSelectionMask: Boolean = false,
    /**
     * Scales every cell while the thumb is pressed, exactly as the reference does.
     *
     * The reference provides `lerp(1f, 1.2f, pressProgress)`, so a press grows the label of every
     * cell — an icon-only cell such as the search glyph included. The iPad top bar must stay still
     * under the finger, so it passes `false`; with it off the cells hold at 1x during the press and
     * only the one cell the thumb settles on grows, briefly, once the settle animation has carried
     * the thumb onto it (after a tap or a completed drag). Defaulted to `true`, so the shipped
     * phone/dual-pane bar keeps the reference's press animation byte-identically.
     */
    pressScalesCells: Boolean = true,
    /**
     * Magnifies the backdrop the selection thumb refracts **with** the thumb instead of against it.
     *
     * The library's reference path counter-transforms the refracted backdrop against the thumb's
     * press bloom; the two do not cancel exactly (the counter-scale is about the layer's origin, the
     * bloom about the thumb's centre), so on a compact bar whose cell is barely wider than its label
     * the recorded copy of the label lands beside the crisp one as a second, ghosted label. With
     * this on the bloom is applied to the thumb alone, so the page and the recorded cells are
     * magnified together — the refracted copy is the label, magnified in place.
     *
     * Off by default, so the shipped phone/dual-pane bar keeps the reference path byte-identically.
     */
    refractionScalesWithThumb: Boolean = false,
    replaceContentUnderThumb: Boolean = false,
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
            effectReferenceHeight = effectReferenceHeight,
            cleanSelectionMask = cleanSelectionMask,
            pressScalesCells = pressScalesCells,
            refractionScalesWithThumb = refractionScalesWithThumb,
            replaceContentUnderThumb = replaceContentUnderThumb,
        ) {
            tabs.forEachIndexed { cellIndex, tab ->
                // Keep each cell's remembered tinted drawable keyed to the tab, so a menu swap can
                // never hand a cell the previous menu's clone.
                key(tab.id) {
                    val tint = if (tintSelectedWithAccent && tab.id == selectedId) accent else foreground
                    LiquidBottomTab(
                        onClick = { request(tab) },
                        index = cellIndex,
                        modifier = Modifier.semantics {
                            selected = tab.id == selectedId
                            contentDescription = tab.title
                        },
                    ) {
                        when (tab.display) {
                            GlassTabDisplay.ICON_AND_TEXT -> {
                                TabIcon(tab.icon, tint)
                                TabLabel(tab.title, tint, tabLabelSize, tabLabelWeight)
                            }

                            GlassTabDisplay.TEXT -> TabLabel(tab.title, tint, tabLabelSize, tabLabelWeight)
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

/**
 * The one-line title, tinted with the cell's colour.
 *
 * [fontSize] and [fontWeight] are the caller's opt-ins: the shared phone bar leaves them at the
 * original 11sp / default weight, and the iPad top bar passes bigger and heavier values.
 */
@Composable
private fun TabLabel(title: String, tint: Color, fontSize: TextUnit, fontWeight: FontWeight?) {
    val style = remember(tint, fontSize, fontWeight) {
        TextStyle(color = tint, fontSize = fontSize, fontWeight = fontWeight)
    }
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
