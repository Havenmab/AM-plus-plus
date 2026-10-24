package dev.amenhancer.lyricsplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Vector glyphs drawn by the page itself; the module has no icon dependency. */
internal enum class PlayerGlyph {
    ChevronDown,
    More,
    Play,
    Pause,
    Previous,
    Next,
    Shuffle,
    Repeat,
    VolumeLow,
    VolumeHigh,
}

/**
 * Paints [glyph] inside the current [DrawScope], centred and sized to `min(width, height)`.
 * Stroke shapes use round caps, matching the iOS chrome.
 */
internal fun DrawScope.drawGlyph(glyph: PlayerGlyph, color: Color) {
    val side = size.minDimension
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val strokeWidth = (side * 0.09f).coerceAtLeast(1f)
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    when (glyph) {
        PlayerGlyph.ChevronDown -> {
            val halfWidth = side * 0.26f
            val halfHeight = side * 0.14f
            val path = Path().apply {
                moveTo(centerX - halfWidth, centerY - halfHeight)
                lineTo(centerX, centerY + halfHeight)
                lineTo(centerX + halfWidth, centerY - halfHeight)
            }
            drawPath(path, color, style = stroke)
        }

        PlayerGlyph.More -> {
            val radius = side * 0.052f
            val gap = side * 0.19f
            drawCircle(color, radius, androidx.compose.ui.geometry.Offset(centerX - gap, centerY))
            drawCircle(color, radius, androidx.compose.ui.geometry.Offset(centerX, centerY))
            drawCircle(color, radius, androidx.compose.ui.geometry.Offset(centerX + gap, centerY))
        }

        PlayerGlyph.Play -> {
            val inset = side * 0.18f
            val path = Path().apply {
                moveTo(centerX - side * 0.22f + inset * 0.4f, centerY - side * 0.28f)
                lineTo(centerX + side * 0.30f, centerY)
                lineTo(centerX - side * 0.22f + inset * 0.4f, centerY + side * 0.28f)
                close()
            }
            drawPath(path, color)
        }

        PlayerGlyph.Pause -> {
            val barWidth = side * 0.13f
            val barHeight = side * 0.52f
            val gap = side * 0.13f
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(centerX - gap / 2f - barWidth, centerY - barHeight / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f),
            )
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(centerX + gap / 2f, centerY - barHeight / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f),
            )
        }

        PlayerGlyph.Previous, PlayerGlyph.Next -> {
            val direction = if (glyph == PlayerGlyph.Next) 1f else -1f
            val barWidth = side * 0.10f
            val barHeight = side * 0.44f
            val triangleHalf = side * 0.20f
            val barX = centerX + direction * (triangleHalf + barWidth * 1.4f)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(
                    if (direction > 0f) barX - barWidth else barX,
                    centerY - barHeight / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f),
            )
            val path = Path().apply {
                moveTo(centerX - direction * triangleHalf, centerY - triangleHalf)
                lineTo(centerX + direction * triangleHalf * 0.55f, centerY)
                lineTo(centerX - direction * triangleHalf, centerY + triangleHalf)
                close()
            }
            drawPath(path, color)
        }

        PlayerGlyph.Shuffle -> {
            val path = Path().apply {
                // Two crossing arrows: bottom-left -> top-right and top-left -> bottom-right.
                moveTo(centerX - side * 0.30f, centerY + side * 0.20f)
                lineTo(centerX + side * 0.22f, centerY - side * 0.20f)
                moveTo(centerX + side * 0.12f, centerY - side * 0.20f)
                lineTo(centerX + side * 0.26f, centerY - side * 0.20f)
                lineTo(centerX + side * 0.26f, centerY - side * 0.06f)
                moveTo(centerX - side * 0.30f, centerY - side * 0.20f)
                lineTo(centerX + side * 0.22f, centerY + side * 0.20f)
                moveTo(centerX + side * 0.12f, centerY + side * 0.20f)
                lineTo(centerX + side * 0.26f, centerY + side * 0.20f)
                lineTo(centerX + side * 0.26f, centerY + side * 0.06f)
            }
            drawPath(path, color, style = stroke)
        }

        PlayerGlyph.Repeat -> {
            val radius = side * 0.26f
            val path = Path().apply {
                addArc(
                    rect = androidx.compose.ui.geometry.Rect(
                        center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                        radius = radius,
                    ),
                    startAngleDegrees = 200f,
                    sweepAngleDegrees = 250f,
                    forceMoveTo = false,
                )
            }
            drawPath(path, color, style = stroke)
            val head = Path().apply {
                val tipX = centerX + radius * 0.92f
                val tipY = centerY - radius * 0.42f
                moveTo(tipX - side * 0.10f, tipY - side * 0.04f)
                lineTo(tipX, tipY)
                lineTo(tipX - side * 0.02f, tipY + side * 0.10f)
            }
            drawPath(head, color, style = stroke)
        }

        PlayerGlyph.VolumeLow, PlayerGlyph.VolumeHigh -> {
            val body = Path().apply {
                moveTo(centerX - side * 0.30f, centerY - side * 0.10f)
                lineTo(centerX - side * 0.18f, centerY - side * 0.10f)
                lineTo(centerX - side * 0.04f, centerY - side * 0.24f)
                lineTo(centerX - side * 0.04f, centerY + side * 0.24f)
                lineTo(centerX - side * 0.18f, centerY + side * 0.10f)
                lineTo(centerX - side * 0.30f, centerY + side * 0.10f)
                close()
            }
            drawPath(body, color)
            for (index in 0 until if (glyph == PlayerGlyph.VolumeHigh) 2 else 1) {
                val waveRadius = side * (0.16f + index * 0.12f)
                val wave = Path().apply {
                    addArc(
                        rect = androidx.compose.ui.geometry.Rect(
                            center = androidx.compose.ui.geometry.Offset(centerX - side * 0.02f, centerY),
                            radius = waveRadius,
                        ),
                        startAngleDegrees = -55f,
                        sweepAngleDegrees = 110f,
                        forceMoveTo = false,
                    )
                }
                drawPath(wave, color, style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round))
            }
        }
    }
}
