package dev.amenhancer.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Every transport/auxiliary action the tablet mini capsule can request from its host. */
enum class GlassMiniPlayerCommand { SHUFFLE, PREVIOUS, PLAY_PAUSE, NEXT, REPEAT, LYRICS, QUEUE }

enum class GlassRepeatMode { OFF, ALL, ONE }

data class GlassMiniPlayerState(
    val isPlaying: Boolean,
    val shuffleOn: Boolean,
    val repeatMode: GlassRepeatMode,
    val enabled: Boolean = true,
)

private const val DISABLED_ALPHA = 0.4f
private const val ARTIST_ALPHA = 0.6f
private const val PLACEHOLDER_ALPHA = 0.08f

/** Stroke width of every glyph, as a fraction of the square canvas. */
private const val GLYPH_STROKE_FRACTION = 0.09f
private const val COVER_INSET_DP = 6
private const val COVER_CORNER_DP = 6
private const val PANEL_PADDING_DP = 6
private const val CONTROL_GAP_DP = 2
private const val CENTER_GAP_DP = 4
private const val TEXT_GAP_DP = 6
private const val TITLE_SIZE_SP = 13f
private const val ARTIST_SIZE_SP = 11f
private val ControlSize = 28.dp

/**
 * The tablet iPad-style BOTTOM mini-player capsule: one horizontal glass capsule with the
 * transport group on the left, the caller-supplied [cover] and a two-line title/artist column in
 * the middle, and the lyrics/queue group on the right.
 *
 * Purely presentational: it holds no playback state, starts no timers, and never touches the host.
 * Glyphs are drawn as simple vector paths so the module ships no new image assets.
 *
 * The capsule fills the width of its [modifier] slot (the centre label column needs a bounded
 * width), so the host sizes it — see [TopBarGeometry.miniPlayerWidthPx] — and centres it.
 */
@Composable
fun GlassMiniPlayer(
    state: GlassMiniPlayerState,
    backdrop: Backdrop,
    accent: Color,
    foreground: Color,
    onCommand: (GlassMiniPlayerCommand) -> Unit,
    cover: (@Composable () -> Unit)?,
    title: String,
    artist: String,
    panelHeight: Dp,
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    modifier: Modifier = Modifier,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = remember(isLightTheme) {
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)
    }
    val titleStyle = remember(foreground) {
        TextStyle(color = foreground, fontSize = TITLE_SIZE_SP.sp)
    }
    val artistStyle = remember(foreground) {
        TextStyle(color = foreground.copy(alpha = ARTIST_ALPHA), fontSize = ARTIST_SIZE_SP.sp)
    }
    val coverSize = (panelHeight - (COVER_INSET_DP * 2).dp).coerceAtLeast(0.dp)
    val coverShape = remember { RoundedCornerShape(COVER_CORNER_DP.dp) }
    val placeholderColor = remember(foreground) { foreground.copy(alpha = PLACEHOLDER_ALPHA) }

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
            .height(panelHeight)
            .fillMaxWidth()
            .padding(horizontal = PANEL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left group: the transport controls, pinned to the leading edge.
        Row(
            horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.SHUFFLE,
                label = "随机播放",
                enabled = state.enabled,
                tint = if (state.shuffleOn) accent else foreground,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.PREVIOUS,
                label = "上一曲",
                enabled = state.enabled,
                tint = foreground,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.PLAY_PAUSE,
                label = if (state.isPlaying) "暂停" else "播放",
                enabled = state.enabled,
                tint = accent,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.NEXT,
                label = "下一曲",
                enabled = state.enabled,
                tint = foreground,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.REPEAT,
                label = "循环播放",
                enabled = state.enabled,
                tint = if (state.repeatMode == GlassRepeatMode.OFF) foreground else accent,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
        }

        // Centre: the artwork slot plus the two-line track label, left-aligned right after the
        // transport group so it dominates the capsule, exactly like the iPad reference.
        Row(
            Modifier.weight(1f).padding(horizontal = CENTER_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(Modifier.size(coverSize).clip(coverShape)) {
                if (cover == null) {
                    Box(Modifier.fillMaxSize().background(placeholderColor, coverShape))
                } else {
                    cover()
                }
            }
            Spacer(Modifier.width(TEXT_GAP_DP.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = artist,
                    style = artistStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Right group: the auxiliary controls, pinned to the trailing edge.
        Row(
            horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.LYRICS,
                label = "歌词",
                enabled = state.enabled,
                tint = foreground,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.QUEUE,
                label = "播放列表",
                enabled = state.enabled,
                tint = foreground,
                isPlaying = state.isPlaying,
                repeatMode = state.repeatMode,
                onCommand = onCommand,
            )
        }
    }
}

/** One icon-only control. Disabled controls are unclickable and dimmed, but keep their semantics. */
@Composable
private fun MiniPlayerControl(
    command: GlassMiniPlayerCommand,
    label: String,
    enabled: Boolean,
    tint: Color,
    isPlaying: Boolean,
    repeatMode: GlassRepeatMode,
    onCommand: (GlassMiniPlayerCommand) -> Unit,
) {
    val send = rememberUpdatedState(onCommand)
    Canvas(
        Modifier
            .size(ControlSize)
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { send.value(command) }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics { contentDescription = label },
    ) {
        drawMiniPlayerGlyph(command, tint, isPlaying, repeatMode)
    }
}

/** Routes a command to its private glyph helper. */
private fun DrawScope.drawMiniPlayerGlyph(
    command: GlassMiniPlayerCommand,
    color: Color,
    isPlaying: Boolean,
    repeatMode: GlassRepeatMode,
) {
    when (command) {
        GlassMiniPlayerCommand.SHUFFLE -> drawShuffleGlyph(color)
        GlassMiniPlayerCommand.PREVIOUS -> drawPreviousGlyph(color)
        GlassMiniPlayerCommand.PLAY_PAUSE ->
            if (isPlaying) drawPauseGlyph(color) else drawPlayGlyph(color)

        GlassMiniPlayerCommand.NEXT -> drawNextGlyph(color)
        GlassMiniPlayerCommand.REPEAT ->
            drawRepeatGlyph(color, one = repeatMode == GlassRepeatMode.ONE)

        GlassMiniPlayerCommand.LYRICS -> drawLyricsGlyph(color)
        GlassMiniPlayerCommand.QUEUE -> drawQueueGlyph(color)
    }
}

private fun DrawScope.glyphStroke(): Float = size.minDimension * GLYPH_STROKE_FRACTION

/** A two-stroke chevron at [tip], opening backwards along [direction]. */
private fun DrawScope.drawArrowHead(color: Color, tip: Offset, direction: Offset, stroke: Float) {
    val length = size.minDimension * 0.20f
    val angle = atan2(direction.y, direction.x)
    val spread = 0.55f
    drawLine(
        color,
        tip,
        tip - Offset(cos(angle - spread), sin(angle - spread)) * length,
        stroke,
        StrokeCap.Round,
    )
    drawLine(
        color,
        tip,
        tip - Offset(cos(angle + spread), sin(angle + spread)) * length,
        stroke,
        StrokeCap.Round,
    )
}

/** Shuffle: two crossing arrows. */
private fun DrawScope.drawShuffleGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = glyphStroke()
    val upTip = Offset(w * 0.80f, h * 0.28f)
    val downTip = Offset(w * 0.80f, h * 0.72f)
    drawLine(color, Offset(w * 0.14f, h * 0.72f), upTip, stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.14f, h * 0.28f), downTip, stroke, StrokeCap.Round)
    drawArrowHead(color, upTip, Offset(1f, -1f), stroke)
    drawArrowHead(color, downTip, Offset(1f, 1f), stroke)
}

/** Previous: a left-pointing triangle behind a leading bar. */
private fun DrawScope.drawPreviousGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = glyphStroke()
    drawLine(color, Offset(w * 0.26f, h * 0.24f), Offset(w * 0.26f, h * 0.76f), stroke, StrokeCap.Round)
    drawPath(
        Path().apply {
            moveTo(w * 0.74f, h * 0.22f)
            lineTo(w * 0.34f, h * 0.50f)
            lineTo(w * 0.74f, h * 0.78f)
            close()
        },
        color,
    )
}

/** Next: a right-pointing triangle behind a trailing bar. */
private fun DrawScope.drawNextGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = glyphStroke()
    drawLine(color, Offset(w * 0.74f, h * 0.24f), Offset(w * 0.74f, h * 0.76f), stroke, StrokeCap.Round)
    drawPath(
        Path().apply {
            moveTo(w * 0.26f, h * 0.22f)
            lineTo(w * 0.66f, h * 0.50f)
            lineTo(w * 0.26f, h * 0.78f)
            close()
        },
        color,
    )
}

/** Play: a filled right-pointing triangle. */
private fun DrawScope.drawPlayGlyph(color: Color) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.78f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.80f)
            close()
        },
        color,
    )
}

/** Pause: two rounded bars. */
private fun DrawScope.drawPauseGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val barWidth = w * 0.14f
    val top = h * 0.22f
    val barHeight = h * 0.56f
    val radius = CornerRadius(barWidth / 2f)
    drawRoundRect(color, Offset(w * 0.28f, top), Size(barWidth, barHeight), radius)
    drawRoundRect(color, Offset(w * 0.58f, top), Size(barWidth, barHeight), radius)
}

/** Repeat: an almost-closed loop with an arrowhead; [one] adds a "1" for the repeat-one mode. */
private fun DrawScope.drawRepeatGlyph(color: Color, one: Boolean) {
    val w = size.width
    val h = size.height
    val stroke = glyphStroke()
    val inset = size.minDimension * 0.18f
    val arcSize = Size(size.minDimension - inset * 2f, size.minDimension - inset * 2f)
    val startAngle = -60f
    val sweepAngle = 300f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    // The arrowhead sits at the arc's end and points the way the loop travels.
    val endRad = (startAngle + sweepAngle) * PI / 180.0
    val radius = arcSize.width / 2f
    val centre = Offset(inset + radius, inset + radius)
    val end = Offset(
        centre.x + radius * cos(endRad).toFloat(),
        centre.y + radius * sin(endRad).toFloat(),
    )
    drawArrowHead(color, end, Offset(-sin(endRad).toFloat(), cos(endRad).toFloat()), stroke)
    if (one) {
        drawLine(color, Offset(w * 0.50f, h * 0.40f), Offset(w * 0.50f, h * 0.62f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.47f), Offset(w * 0.50f, h * 0.40f), stroke, StrokeCap.Round)
    }
}

/** Lyrics: a speech bubble with two text lines. */
private fun DrawScope.drawLyricsGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = glyphStroke()
    val left = w * 0.16f
    val top = h * 0.22f
    val right = w * 0.84f
    val bottom = h * 0.68f
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(size.minDimension * 0.16f),
        style = Stroke(width = stroke),
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.30f, bottom)
            lineTo(w * 0.28f, h * 0.86f)
            lineTo(w * 0.48f, bottom)
            close()
        },
        color,
    )
    drawLine(color, Offset(w * 0.30f, h * 0.37f), Offset(w * 0.70f, h * 0.37f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.30f, h * 0.52f), Offset(w * 0.58f, h * 0.52f), stroke, StrokeCap.Round)
}

/** Queue: three stacked lines. */
private fun DrawScope.drawQueueGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = glyphStroke()
    drawLine(color, Offset(w * 0.24f, h * 0.30f), Offset(w * 0.78f, h * 0.30f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.24f, h * 0.50f), Offset(w * 0.78f, h * 0.50f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.24f, h * 0.70f), Offset(w * 0.62f, h * 0.70f), stroke, StrokeCap.Round)
}
