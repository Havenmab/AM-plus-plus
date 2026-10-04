package dev.amenhancer.glass

import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle

/** Every transport/auxiliary action the tablet mini capsule can request from its host. */
enum class GlassMiniPlayerCommand { SHUFFLE, PREVIOUS, PLAY_PAUSE, NEXT, REPEAT, MORE, LYRICS, QUEUE }

enum class GlassRepeatMode { OFF, ALL, ONE }

data class GlassMiniPlayerState(
    val isPlaying: Boolean,
    val shuffleOn: Boolean,
    val repeatMode: GlassRepeatMode,
    val enabled: Boolean = true,
    val moreEnabled: Boolean = enabled,
)

/** Host drawables loaded by the caller from the Apple Music package; anything null draws nothing. */
data class GlassMiniPlayerIcons(
    val shuffle: Drawable?,
    val previous: Drawable?,
    val play: Drawable?,
    val pause: Drawable?,
    val next: Drawable?,
    val repeat: Drawable?,
    val repeatOne: Drawable?,
    val more: Drawable?,
    val lyrics: Drawable?,
    val queue: Drawable?,
)

private const val DISABLED_ALPHA = 0.4f
private const val ARTIST_ALPHA = 0.6f
private const val PLACEHOLDER_ALPHA = 0.08f

// iPad reference proportions, all measured against the capsule's own height H (86px art / 56dp bar
// here): the artwork is a small rounded square, the transport glyphs are pitched ~0.70H apart, and
// the centre block is separated from the transport and aux groups without being a lone cluster.
private const val COVER_SIZE_FRACTION = 0.68f
private const val COVER_CORNER_FRACTION = 0.22f
private const val PANEL_PADDING_DP = 15 // iPad: ~0.28 x capsule height of glass at each end
private const val CONTROL_GAP_DP = 11 // iPad: glyph pitch ~0.70H less the 28dp control box
private const val CENTER_GAP_DP = 10 // iPad: ~0.19 x capsule height around the centre block
private const val TEXT_GAP_DP = 8 // iPad: ~0.14 x capsule height, artwork -> title
private const val TITLE_SIZE_SP = 15f // iPad: title cap ~0.16 x capsule height, the bolder line
private const val ARTIST_SIZE_SP = 13f // iPad: artist x-height matches the title's, so nearly as big
private val ControlSize = 28.dp
private val PlayControlSize = 48.dp // iPad: play/pause glyph ~0.44 x capsule height vs ~0.25 for prev/next

/**
 * The tablet iPad-style BOTTOM mini-player capsule: one horizontal glass capsule with the
 * transport group on the left, the caller-supplied [cover] and a two-line title/artist column in
 * the middle, and the lyrics/queue group on the right.
 *
 * Purely presentational: it holds no playback state, starts no timers, and never touches the host.
 * Every control glyph is a host [Drawable] from [icons] (loaded from the Apple Music package), so
 * the capsule shows the app's real artwork rather than stand-in vector paths.
 *
 * The capsule fills the width of its [modifier] slot (the centre label column needs a bounded
 * width), so the host sizes it — see [TopBarGeometry.miniPlayerWidthPx] — and centres it.
 * Tapping the body (artwork/title/artist) calls [onExpand]; the controls never do.
 *
 * The surface fills its parent, so a host that animates the capsule's View height (the tablet
 * session grows the collapsed band into the full player) gets content that follows the View.
 * [panelHeight] is the **collapsed** reference only: the artwork size and the collapsed corner
 * radius are measured against it, exactly as [NativeLiquidButton] measures its `miniHeightDp`.
 * [expansion] reports how far the host has expanded the surface (`0` = collapsed capsule, `1` =
 * full player) so the material's shape can interpolate like `NLB`'s does.
 *
 * [labelTypeface] is the host's own typeface for the title/artist column; when null the default
 * family is used.
 */
@Composable
fun GlassMiniPlayer(
    state: GlassMiniPlayerState,
    backdrop: Backdrop,
    icons: GlassMiniPlayerIcons,
    accent: Color,
    foreground: Color,
    onCommand: (GlassMiniPlayerCommand) -> Unit,
    onExpand: () -> Unit,
    cover: (@Composable () -> Unit)?,
    title: String,
    artist: String,
    panelHeight: Dp,
    expansion: Float = 0f,
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    modifier: Modifier = Modifier,
    labelTypeface: Typeface? = null,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = remember(isLightTheme) {
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)
    }
    // The caller hands over the host's own Apple Music typeface (resolved from its native mini-player
    // title view), so Latin renders in the app's SF Pro and CJK still falls back to the system font.
    // Null keeps the default family, i.e. exactly the previous look.
    val labelFontFamily = remember(labelTypeface) {
        labelTypeface?.let { FontFamily(it) }
    }
    val titleStyle = remember(foreground, labelFontFamily) {
        TextStyle(
            color = foreground,
            fontSize = TITLE_SIZE_SP.sp,
            fontFamily = labelFontFamily,
            fontWeight = FontWeight.Medium,
        )
    }
    val artistStyle = remember(foreground, labelFontFamily) {
        TextStyle(
            color = foreground.copy(alpha = ARTIST_ALPHA),
            fontSize = ARTIST_SIZE_SP.sp,
            fontFamily = labelFontFamily,
            fontWeight = FontWeight.Medium,
        )
    }
    val coverSize = (panelHeight * COVER_SIZE_FRACTION).coerceAtLeast(0.dp)
    val coverShape = remember(coverSize) {
        RoundedRectangle(coverSize * COVER_CORNER_FRACTION, RoundedCornerStyle.Continuous)
    }
    val placeholderColor = remember(foreground) { foreground.copy(alpha = PLACEHOLDER_ALPHA) }
    val expand = rememberUpdatedState(onExpand)
    // The material shape tracks the host's expansion the same way NativeLiquidButton's does: a
    // capsule when collapsed, a large rounded rect once the surface has grown into the player.
    // `expansion == 0` keeps the exact `Capsule()` the collapsed capsule shipped with.
    val clampedExpansion = expansion.coerceIn(0f, 1f)
    val shape: Shape = remember(clampedExpansion, panelHeight) {
        if (clampedExpansion == 0f) {
            Capsule()
        } else {
            RoundedCornerShape((panelHeight.value / 2f + (24f - panelHeight.value / 2f) * clampedExpansion).dp)
        }
    }

    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
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
            // Fill the host's animated slot: the session grows this View from the collapsed band
            // into the full player, and the content must follow that height rather than stay at
            // the collapsed `panelHeight`.
            .fillMaxSize()
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
                icon = icons.shuffle,
                tint = if (state.shuffleOn) accent else foreground,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.PREVIOUS,
                label = "上一曲",
                enabled = state.enabled,
                icon = icons.previous,
                tint = foreground,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.PLAY_PAUSE,
                label = if (state.isPlaying) "暂停" else "播放",
                enabled = state.enabled,
                icon = if (state.isPlaying) icons.pause else icons.play,
                tint = foreground,
                controlSize = PlayControlSize,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.NEXT,
                label = "下一曲",
                enabled = state.enabled,
                icon = icons.next,
                tint = foreground,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.REPEAT,
                label = "循环播放",
                enabled = state.enabled,
                icon = when (state.repeatMode) {
                    GlassRepeatMode.OFF -> icons.repeat
                    GlassRepeatMode.ALL -> icons.repeat
                    GlassRepeatMode.ONE -> icons.repeatOne
                },
                tint = if (state.repeatMode == GlassRepeatMode.OFF) foreground else accent,
                onCommand = onCommand,
            )
        }

        // Centre: the artwork slot plus the two-line track label, left-aligned right after the
        // transport group so it dominates the capsule, exactly like the iPad reference. Only this
        // body is tappable-to-expand, so the control buttons never also fire it.
        Row(
            Modifier
                .weight(1f)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                ) { expand.value() }
                .padding(horizontal = CENTER_GAP_DP.dp),
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
                command = GlassMiniPlayerCommand.MORE,
                label = "更多歌曲操作",
                enabled = state.enabled && state.moreEnabled,
                icon = icons.more,
                tint = foreground,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.LYRICS,
                label = "歌词",
                enabled = state.enabled,
                icon = icons.lyrics,
                tint = foreground,
                onCommand = onCommand,
            )
            MiniPlayerControl(
                command = GlassMiniPlayerCommand.QUEUE,
                label = "播放列表",
                enabled = state.enabled,
                icon = icons.queue,
                tint = foreground,
                onCommand = onCommand,
            )
        }
    }
}

/**
 * One icon-only control. Disabled controls are unclickable and dimmed, but keep their semantics.
 * The host's [Drawable] is cloned and tinted (never mutated in place) and anything null draws
 * nothing.
 */
@Composable
private fun MiniPlayerControl(
    command: GlassMiniPlayerCommand,
    label: String,
    enabled: Boolean,
    icon: Drawable?,
    tint: Color,
    onCommand: (GlassMiniPlayerCommand) -> Unit,
    controlSize: Dp = ControlSize,
) {
    val send = rememberUpdatedState(onCommand)
    val tinted = remember(icon, tint) { tintedDrawable(icon, tint) }
    Canvas(
        Modifier
            .size(controlSize)
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { send.value(command) }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics { contentDescription = label },
    ) {
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
