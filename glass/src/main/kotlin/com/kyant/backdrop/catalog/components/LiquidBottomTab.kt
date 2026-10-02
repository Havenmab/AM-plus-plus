package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

/**
 * AM++: a cell's scale for the current frame, addressed by the cell's index. Keyed by index so a
 * caller can scale only the one cell the selection thumb has settled on (see
 * `LiquidBottomTabs(pressScalesCells = false)`); the default returns `1f` for every index, i.e. the
 * shipped neutral scale.
 */
internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf<(Int) -> Float> { { 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * AM++: this cell's zero-based position in the row, handed to [LocalLiquidBottomTabScale].
     * Defaulted to `0` so an unchanged call site keeps the previous index-agnostic behavior.
     */
    index: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val cellScale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = cellScale(index)
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
