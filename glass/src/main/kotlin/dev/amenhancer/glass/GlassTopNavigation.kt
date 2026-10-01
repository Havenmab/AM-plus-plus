package dev.amenhancer.glass

import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/** How a top tab paints itself: a plain title or a tinted icon. */
enum class GlassTopTabDisplay { TEXT, ICON }

data class GlassTopTab(
    val id: Int,
    val title: String,
    val icon: Drawable?,
    val enabled: Boolean,
    val display: GlassTopTabDisplay,
)

/** A selected cell is washed with a low-alpha accent capsule; inactive cells stay bare. */
private const val SELECTED_TINT_ALPHA = 0.18f
private const val DISABLED_ALPHA = 0.4f
private val TopTabCellPadding = 18.dp
private val TopTabGap = 2.dp
private val TopTabPillInsetX = 2.dp
private val TopTabPillInsetY = 3.dp
private val TopTabIconSize = 20.dp
private val TopTabLabelSize = 14.sp

/**
 * The tablet iPad-style TOP navigation capsule. One horizontal glass capsule that hugs its
 * content, with uniformly padded cells; the selected cell carries an accent pill and every cell is
 * a `Role.Tab`.
 *
 * The iPad top bar has plain tabs plus a single trailing icon, no draggable thumb, so this
 * deliberately does not use `LiquidBottomTabs`. It also never renders a sidebar toggle: the host
 * simply does not pass one. The capsule hugs its content, so the host decides where it sits.
 *
 * Selection is hoisted: [onSelect] returns the id the host accepted, and only a tap whose return
 * value equals the tab's id may move the highlight.
 */
@Composable
fun GlassTopNavigation(
    tabs: List<GlassTopTab>,
    selectedId: Int,
    accent: Color,
    foreground: Color,
    backdrop: Backdrop,
    onSelect: (Int) -> Int,
    panelHeight: Dp,
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    modifier: Modifier = Modifier,
) {
    val select = rememberUpdatedState(onSelect)
    // The host owns selection, so a rejected tap must leave the previous cell painted as selected.
    // Re-keying on `selectedId` resyncs as soon as the host confirms a new one.
    var shownId by remember(selectedId, tabs) { mutableIntStateOf(selectedId) }
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = remember(isLightTheme) {
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)
    }
    val pillColor = remember(accent) { accent.copy(alpha = SELECTED_TINT_ALPHA) }
    val labelStyle = remember(foreground) {
        TextStyle(color = foreground, fontSize = TopTabLabelSize)
    }
    // Drawable exposes no public tint getters, so never mutate the host's icon: clone each icon and
    // tint the clone. Keyed on the tab list and foreground, so this never runs per frame.
    val tintedIcons = remember(tabs, foreground) {
        tabs.map { tab ->
            tab.icon?.constantState?.newDrawable()?.mutate()?.apply {
                setTint(foreground.toArgb())
                setTintMode(PorterDuff.Mode.SRC_IN)
            }
        }
    }

    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(panelBlur.toPx())
                    // Keep the capsule centre outside refraction on short bars.
                    val refraction = minOf(24f.dp.toPx(), size.minDimension * 0.375f)
                    lens(refraction, refraction)
                },
                onDrawSurface = { drawRect(containerColor) },
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            // No fillMaxWidth: the capsule hugs its tabs so the host can centre a compact pill.
            .height(panelHeight),
        horizontalArrangement = Arrangement.spacedBy(TopTabGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No early return for small counts: a two-tab (or one-tab) top bar still renders.
        tabs.forEachIndexed { index, tab ->
            val isSelected = tab.id == shownId
            Box(
                Modifier
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        enabled = tab.enabled,
                        role = Role.Tab,
                    ) {
                        if (select.value(tab.id) == tab.id) shownId = tab.id
                    }
                    .semantics {
                        selected = isSelected
                        contentDescription = tab.title
                    }
                    .alpha(if (tab.enabled) 1f else DISABLED_ALPHA),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    // matchParentSize keeps this background out of the cell's own measurement, so
                    // the capsule still hugs its tabs; it sits behind this cell's content.
                    Box(
                        Modifier
                            .matchParentSize()
                            .padding(horizontal = TopTabPillInsetX, vertical = TopTabPillInsetY)
                            .background(pillColor, Capsule()),
                    )
                }
                when (tab.display) {
                    GlassTopTabDisplay.TEXT -> BasicText(
                        text = tab.title,
                        modifier = Modifier.padding(horizontal = TopTabCellPadding),
                        style = labelStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    GlassTopTabDisplay.ICON -> Canvas(
                        Modifier
                            .padding(horizontal = TopTabCellPadding)
                            .size(TopTabIconSize),
                    ) {
                        tintedIcons.getOrNull(index)?.let { icon ->
                            val canvas = drawContext.canvas.nativeCanvas
                            val save = canvas.save()
                            try {
                                icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                icon.draw(canvas)
                            } finally {
                                canvas.restoreToCount(save)
                            }
                        }
                    }
                }
            }
        }
    }
}
