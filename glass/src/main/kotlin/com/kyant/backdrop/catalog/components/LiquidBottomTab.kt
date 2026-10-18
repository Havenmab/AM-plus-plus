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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Capsule(),
    /**
     * AM++: the cell's share of the row. Every equal-width caller keeps upstream's literal 1f;
     * the tablet top bar hands each cell the weight its measured content earned
     * ([dev.amenhancer.glass.GlassTabGeometry.cellWeight]) so the row can hug its labels.
     */
    weight: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(shape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(weight)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
