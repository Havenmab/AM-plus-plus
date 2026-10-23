package dev.amenhancer.lyricsplayer

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.lyrics.player.LyricsPlayerFormat
import dev.amenhancer.module.lyrics.player.PlayerTrackInfo
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * The whole lyrics page: blurred cover, top bar, self-drawn lyrics, transport.
 *
 * The host mounts this behind an opaque, clickable container, so the page owns every touch
 * (spec §1 D2). Everything it needs comes from [LyricsPlayerPort]; nothing here talks to the
 * host application directly.
 */
@Composable
internal fun LyricsPlayerPage(
    port: LyricsPlayerPort?,
    insets: LyricsPlayerInsets,
    visible: Boolean,
) {
    if (port == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val runtime = remember(port) { LyricsPlayerRuntime(port) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val windowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val playing by runtime.playingState
    val track = runtime.trackState.value
    val artwork = runtime.artworkState.value

    // The frame loop is the page clock: one host sample, one spring step, one draw.
    LaunchedEffect(runtime, visible) {
        if (!visible) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { nowNanos -> runtime.onFrame(nowNanos) }
        }
    }

    val topInset = with(density) { insets.topPx.toDp() }
    val bottomInset = with(density) { insets.bottomPx.toDp() }
    val startInset = with(density) { insets.leftPx.toDp() }
    val endInset = with(density) { insets.rightPx.toDp() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LyricsPlayerBackground(artwork = artwork, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = startInset, end = endInset),
        ) {
            LyricsPlayerTopBar(port = port, topInset = topInset, track = track)
            LyricsPlayerLyrics(
                runtime = runtime,
                windowHeightPx = windowHeightPx,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            LyricsPlayerTransport(
                port = port,
                runtime = runtime,
                playing = playing,
                bottomInset = bottomInset,
            )
        }
    }
}

@Composable
private fun LyricsPlayerBackground(artwork: ImageBitmap?, modifier: Modifier = Modifier) {
    val background = rememberLegacyBlurFallback(artwork)
    val scrim = remember {
        Brush.verticalGradient(
            *LyricsPlayerChromeSpec.SCRIM_COLORS.map { (position, argb) ->
                position to Color(argb)
            }.toTypedArray(),
        )
    }
    Box(
        modifier.background(
            Brush.verticalGradient(0f to FALLBACK_TOP, 1f to FALLBACK_BOTTOM),
        ),
    ) {
        if (background != null) {
            Image(
                bitmap = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = LyricsPlayerChromeSpec.BACKGROUND_SCALE
                        scaleY = LyricsPlayerChromeSpec.BACKGROUND_SCALE
                    }
                    .blur(LyricsPlayerChromeSpec.BACKGROUND_BLUR),
            )
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

/**
 * API 31+ can blur the real bitmap; older devices get a 1/16 downscale that the image
 * scaler turns into a soft wash. Either way the work happens once per track.
 */
@Composable
private fun rememberLegacyBlurFallback(artwork: ImageBitmap?): ImageBitmap? = remember(artwork) {
    if (artwork == null) {
        null
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        artwork
    } else {
        runCatching {
            val source = artwork.asAndroidBitmap()
            val width = max(source.width / LyricsPlayerChromeSpec.BACKGROUND_LEGACY_DOWNSCALE, 16)
            val height = max(source.height / LyricsPlayerChromeSpec.BACKGROUND_LEGACY_DOWNSCALE, 16)
            Bitmap.createScaledBitmap(source, width, height, true).asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun LyricsPlayerTopBar(port: LyricsPlayerPort, topInset: Dp, track: PlayerTrackInfo?) {
    var moreVisible by remember { mutableStateOf(true) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LyricsPlayerChromeSpec.TOP_BAR_HEIGHT + topInset)
            .padding(
                top = topInset,
                start = LyricsPlayerChromeSpec.TOP_BAR_HORIZONTAL_PADDING,
                end = LyricsPlayerChromeSpec.TOP_BAR_HORIZONTAL_PADDING,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeButton(glyph = PlayerGlyph.ChevronDown, label = "退出歌词页") {
            if (!port.closePage()) Log.w(LOG_TAG, "closePage refused by host")
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = "正在播放",
                style = TextStyle(
                    color = Color.White,
                    fontSize = LyricsPlayerChromeSpec.TITLE_SIZE,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
            )
            if (track != null && track.artist.isNotBlank()) {
                BasicText(
                    text = track.artist,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = LyricsPlayerChromeSpec.SUBTITLE_SIZE,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (moreVisible) {
            ChromeButton(glyph = PlayerGlyph.More, label = "更多") {
                if (!port.more()) moreVisible = false
            }
        } else {
            Spacer(Modifier.size(LyricsPlayerChromeSpec.TOP_BAR_BUTTON))
        }
    }
}

@Composable
private fun ChromeButton(glyph: PlayerGlyph, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(LyricsPlayerChromeSpec.TOP_BAR_BUTTON)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(LyricsPlayerChromeSpec.TOP_BAR_ICON)) {
            drawGlyph(glyph, Color.White)
        }
    }
}

@Composable
private fun LyricsPlayerTransport(
    port: LyricsPlayerPort,
    runtime: LyricsPlayerRuntime,
    playing: Boolean,
    bottomInset: Dp,
) {
    // A host that refuses a control greys it out for the rest of this page (spec §7.4).
    var previousEnabled by remember { mutableStateOf(true) }
    var playEnabled by remember { mutableStateOf(true) }
    var nextEnabled by remember { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset),
    ) {
        LyricsPlayerProgress(port = port, runtime = runtime)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(LyricsPlayerChromeSpec.TRANSPORT_HEIGHT),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                glyph = PlayerGlyph.Previous,
                label = "上一首",
                enabled = previousEnabled,
            ) {
                if (!port.skipPrevious()) previousEnabled = false
            }
            Spacer(Modifier.width(LyricsPlayerChromeSpec.TRANSPORT_GAP))
            TransportButton(
                glyph = if (playing) PlayerGlyph.Pause else PlayerGlyph.Play,
                label = if (playing) "暂停" else "播放",
                enabled = playEnabled,
            ) {
                if (!port.playPause()) playEnabled = false
            }
            Spacer(Modifier.width(LyricsPlayerChromeSpec.TRANSPORT_GAP))
            TransportButton(
                glyph = PlayerGlyph.Next,
                label = "下一首",
                enabled = nextEnabled,
            ) {
                if (!port.skipNext()) nextEnabled = false
            }
        }
        Spacer(Modifier.height(LyricsPlayerChromeSpec.BOTTOM_PADDING))
    }
}

@Composable
private fun TransportButton(
    glyph: PlayerGlyph,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(LyricsPlayerChromeSpec.TRANSPORT_BUTTON)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(LyricsPlayerChromeSpec.TRANSPORT_ICON)) {
            drawGlyph(glyph, if (enabled) Color.White else Color.White.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun LyricsPlayerProgress(port: LyricsPlayerPort, runtime: LyricsPlayerRuntime) {
    val durationMs = runtime.durationMsState.value
    val elapsedMs = runtime.elapsedSecondsState.value * 1000L
    var previewFraction by remember { mutableStateOf<Float?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LyricsPlayerChromeSpec.PROGRESS_TOUCH_HEIGHT)
            .padding(horizontal = LyricsPlayerChromeSpec.PROGRESS_HORIZONTAL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(durationMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun fractionAt(x: Float): Float =
                            (x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        previewFraction = fractionAt(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                if (durationMs > 0L) {
                                    port.seekTo(((previewFraction ?: 0f) * durationMs.toFloat()).toLong())
                                }
                                previewFraction = null
                                change.consume()
                                break
                            }
                            previewFraction = fractionAt(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            // Draw-phase read: the progress line follows the frame clock, not recomposition.
            runtime.tick.value
            val duration = durationMs.coerceAtLeast(0L)
            val positionMs = previewFraction?.let { (it * duration).toLong() }
                ?: runtime.clock.visualPositionMs()
            val fraction = if (duration > 0L) {
                (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val centerY = size.height * 0.5f
            val trackHeight = LyricsPlayerChromeSpec.PROGRESS_TRACK_HEIGHT.toPx()
            val radius = LyricsPlayerChromeSpec.PROGRESS_THUMB_RADIUS.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(0f, centerY - trackHeight * 0.5f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight * 0.5f),
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.92f),
                    topLeft = Offset(0f, centerY - trackHeight * 0.5f),
                    size = Size(size.width * fraction, trackHeight),
                    cornerRadius = CornerRadius(trackHeight * 0.5f),
                )
            }
            val thumbX = if (size.width <= radius * 2f) {
                size.width * 0.5f
            } else {
                (size.width * fraction).coerceIn(radius, size.width - radius)
            }
            drawCircle(color = Color.White, radius = radius, center = Offset(thumbX, centerY))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(
            text = LyricsPlayerFormat.position(
                previewFraction?.let { (it * durationMs).toLong() } ?: elapsedMs,
            ),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.55f),
                fontSize = LyricsPlayerChromeSpec.TIME_SIZE,
            ),
            maxLines = 1,
        )
        if (durationMs > 0L) {
            BasicText(
                text = " / " + LyricsPlayerFormat.position(durationMs),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = LyricsPlayerChromeSpec.TIME_SIZE,
                ),
                maxLines = 1,
            )
        }
    }
}

private const val LOG_TAG = "lyrics player"
private val FALLBACK_TOP = Color(0xFF2B2B31.toInt())
private val FALLBACK_BOTTOM = Color(0xFF08080A.toInt())
