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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amenhancer.module.lyrics.player.LyricsPlayerColumns
import dev.amenhancer.module.lyrics.player.LyricsPlayerFormat
import dev.amenhancer.module.lyrics.player.LyricsPlayerLayoutMode
import dev.amenhancer.module.lyrics.player.PlayerTrackInfo
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The v2 player page: LyricsBlossom layout.
 *
 * Tablet (two panes): cover + metadata + progress + transport + volume + footer on the left,
 * a left-aligned lyrics column on the right. Phone: compact cover header, full-width lyrics,
 * progress and transport at the bottom. The exit affordance is a single small button in the
 * top-right corner so it never covers the reference layout (VISUAL-SPEC sections 5-6).
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
    val fonts = rememberLyricsPlayerFonts(port)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val playing by runtime.playingState
    val track = runtime.trackState.value
    val artwork = runtime.artworkState.value

    LaunchedEffect(runtime, visible) {
        if (!visible) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { nowNanos -> runtime.onFrame(nowNanos) }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val windowWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        LyricsPlayerBackground(
            artwork = artwork,
            viewportWidthPx = windowWidthPx,
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(
                    start = with(density) { insets.leftPx.toDp() },
                    end = with(density) { insets.rightPx.toDp() },
                    top = with(density) { insets.topPx.toDp() },
                    bottom = with(density) { insets.bottomPx.toDp() },
                ),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val mode = LyricsPlayerLayoutMode.forViewport(configuration.smallestScreenWidthDp)
            val config = runtime.configState.value
            val columns = remember(widthPx, heightPx, density, mode, config.fontScalePercent) {
                LyricsPlayerColumns(
                    viewportWidthPx = widthPx,
                    viewportHeightPx = heightPx,
                    density = density.density,
                    mode = mode,
                    fontScalePercent = config.fontScalePercent,
                )
            }
            if (columns.twoPane) {
                TwoPaneContent(
                    port = port,
                    runtime = runtime,
                    columns = columns,
                    fonts = fonts,
                    track = track,
                    playing = playing,
                    artwork = artwork,
                )
            } else {
                SingleColumnContent(
                    port = port,
                    runtime = runtime,
                    columns = columns,
                    fonts = fonts,
                    track = track,
                    playing = playing,
                    artwork = artwork,
                )
            }
            ExitButton(
                port = port,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Tablet: two panes
// ---------------------------------------------------------------------------------------

@Composable
private fun TwoPaneContent(
    port: LyricsPlayerPort,
    runtime: LyricsPlayerRuntime,
    columns: LyricsPlayerColumns,
    fonts: LyricsPlayerFonts,
    track: PlayerTrackInfo?,
    playing: Boolean,
    artwork: ImageBitmap?,
) {
    val density = LocalDensity.current
    val preview = remember { SeekPreview() }
    Box(Modifier.fillMaxSize()) {
        // The reference darkens the cover pane more than the lyrics side (flat scrim, no
        // gradient - VISUAL-SPEC section 9 explicitly rejects a linear-gradient background).
        Box(
            Modifier
                .fillMaxHeight()
                .width(pxToDp(columns.leftColumnRightPx * 1.06f, density))
                .background(Color.Black.copy(alpha = 0.20f)),
        )
        CoverArt(
            artwork = artwork,
            columns = columns,
            fonts = fonts,
            modifier = Modifier
                .offset { IntOffset(columns.coverLeftPx.roundToInt(), columns.coverTopPx.roundToInt()) }
                .size(pxToDp(columns.coverSizePx, density))
                .clip(RoundedCornerShape(pxToDp(columns.coverCornerRadiusPx, density))),
        )
        TrackTexts(
            track = track,
            columns = columns,
            fonts = fonts,
            titleTopPx = columns.titleTopPx,
            artistTopPx = columns.artistTopPx,
            textLeftPx = columns.coverLeftPx,
            textWidthPx = columns.leftColumnWidthPx - columns.footerFontPx * 2f,
            titleSizePx = columns.titleFontPx,
            artistSizePx = columns.artistFontPx,
            density = density,
        )
        MenuButton(
            port = port,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (columns.leftColumnRightPx - pxToPx(40.dp, density)).roundToInt(),
                        (columns.menuCenterYPx - pxToPx(20.dp, density)).roundToInt(),
                    )
                }
                .size(40.dp),
        )
        ProgressSeekBar(
            port = port,
            runtime = runtime,
            preview = preview,
            widthPx = columns.leftColumnWidthPx,
            barHeightPx = columns.progressBarHeightPx,
            thumbRadiusPx = columns.progressThumbRadiusPx,
            modifier = Modifier
                .offset {
                    IntOffset(
                        columns.coverLeftPx.roundToInt(),
                        (columns.progressCenterYPx - dpValueToPx(BAR_TOUCH_DP, density) * 0.5f).roundToInt(),
                    )
                },
        )
        TimesRow(
            runtime = runtime,
            preview = preview,
            timeSizePx = columns.timesFontPx,
            modifier = Modifier
                .offset {
                    IntOffset(
                        columns.coverLeftPx.roundToInt(),
                        (columns.timesCenterYPx - columns.timesFontPx * 0.6f).roundToInt(),
                    )
                }
                .width(pxToDp(columns.leftColumnWidthPx, density)),
        )
        TransportRow(
            port = port,
            playing = playing,
            columns = columns,
            modifier = Modifier
                .offset {
                    IntOffset(
                        columns.coverLeftPx.roundToInt(),
                        (columns.transportCenterYPx - columns.playIconPx * 0.5f).roundToInt(),
                    )
                }
                .width(pxToDp(columns.leftColumnWidthPx, density))
                .height(pxToDp(columns.playIconPx, density)),
            expanded = true,
        )
        VolumeRow(
            columns = columns,
            modifier = Modifier
                .offset {
                    IntOffset(
                        columns.coverLeftPx.roundToInt(),
                        (columns.volumeCenterYPx - columns.volumeBarHeightPx * 2f).roundToInt(),
                    )
                }
                .width(pxToDp(columns.leftColumnWidthPx, density)),
        )
        FooterRow(
            columns = columns,
            fonts = fonts,
            modifier = Modifier
                .offset {
                    IntOffset(
                        columns.coverLeftPx.roundToInt(),
                        (columns.footerCenterYPx - columns.footerFontPx * 0.7f).roundToInt(),
                    )
                }
                .width(pxToDp(columns.leftColumnWidthPx, density)),
        )
        LyricsPlayerLyrics(
            runtime = runtime,
            columns = columns,
            fonts = fonts,
            modifier = Modifier
                .offset { IntOffset(columns.lyricsLeftPx.roundToInt(), columns.lyricsTopPx.roundToInt()) }
                .size(
                    pxToDp(columns.lyricsWidthPx, density),
                    pxToDp(columns.lyricsHeightPx, density),
                ),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Phone: single column
// ---------------------------------------------------------------------------------------

@Composable
private fun SingleColumnContent(
    port: LyricsPlayerPort,
    runtime: LyricsPlayerRuntime,
    columns: LyricsPlayerColumns,
    fonts: LyricsPlayerFonts,
    track: PlayerTrackInfo?,
    playing: Boolean,
    artwork: ImageBitmap?,
) {
    val density = LocalDensity.current
    val preview = remember { SeekPreview() }
    Box(Modifier.fillMaxSize()) {
        CoverArt(
            artwork = artwork,
            columns = columns,
            fonts = fonts,
            modifier = Modifier
                .offset { IntOffset(columns.phoneCoverLeftPx.roundToInt(), columns.phoneCoverTopPx.roundToInt()) }
                .size(pxToDp(columns.phoneCoverSizePx, density))
                .clip(RoundedCornerShape(pxToDp(columns.coverCornerRadiusPx, density))),
        )
        TrackTexts(
            track = track,
            columns = columns,
            fonts = fonts,
            titleTopPx = columns.titleTopPx,
            artistTopPx = columns.artistTopPx,
            textLeftPx = columns.headerTextLeftPx,
            textWidthPx = columns.lyricsRightPx - columns.headerTextLeftPx - pxToPx(48.dp, density),
            titleSizePx = columns.titleFontPx,
            artistSizePx = columns.artistFontPx,
            density = density,
        )
        LyricsPlayerLyrics(
            runtime = runtime,
            columns = columns,
            fonts = fonts,
            modifier = Modifier
                .offset { IntOffset(columns.lyricsLeftPx.roundToInt(), columns.lyricsTopPx.roundToInt()) }
                .size(
                    pxToDp(columns.lyricsWidthPx, density),
                    pxToDp(columns.lyricsHeightPx, density),
                ),
        )
        // Bottom block: progress, times, transport.
        Box(
            Modifier
                .offset { IntOffset(0, columns.bottomBlockTopPx.roundToInt()) }
                .fillMaxWidth()
                .height(pxToDp(columns.bottomBlockHeightPx, density)),
        ) {
            ProgressSeekBar(
                port = port,
                runtime = runtime,
                preview = preview,
                widthPx = columns.lyricsWidthPx,
                barHeightPx = columns.progressBarHeightPx,
                thumbRadiusPx = columns.progressThumbRadiusPx,
                modifier = Modifier.offset { IntOffset(columns.lyricsLeftPx.roundToInt(), 0) },
            )
            TimesRow(
                runtime = runtime,
                preview = preview,
                timeSizePx = columns.timesFontPx,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            columns.lyricsLeftPx.roundToInt(),
                            (dpValueToPx(BAR_TOUCH_DP, density) * 0.95f).roundToInt(),
                        )
                    }
                    .width(pxToDp(columns.lyricsWidthPx, density)),
            )
            TransportRow(
                port = port,
                playing = playing,
                columns = columns,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(pxToDp(columns.playIconPx * 1.8f, density)),
                expanded = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Shared chrome
// ---------------------------------------------------------------------------------------

private class SeekPreview {
    var fraction by mutableStateOf<Float?>(null)
}

@Composable
private fun CoverArt(
    artwork: ImageBitmap?,
    columns: LyricsPlayerColumns,
    fonts: LyricsPlayerFonts,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    Box(
        modifier.background(
            Brush.verticalGradient(0f to Color(0xFF24272C), 1f to Color(0xFF0C0E11)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BasicText(
                text = "♪",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = with(density) { (columns.coverSizePx * 0.28f).toSp() },
                    fontFamily = fonts.regular,
                ),
            )
        }
    }
}

@Composable
private fun TrackTexts(
    track: PlayerTrackInfo?,
    columns: LyricsPlayerColumns,
    fonts: LyricsPlayerFonts,
    titleTopPx: Float,
    artistTopPx: Float,
    textLeftPx: Float,
    textWidthPx: Float,
    titleSizePx: Float,
    artistSizePx: Float,
    density: Density,
) {
    if (track == null) return
    BasicText(
        text = track.title,
        style = TextStyle(
            color = Color.White,
            fontSize = with(density) { titleSizePx.toSp() },
            fontFamily = fonts.semibold,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .offset { IntOffset(textLeftPx.roundToInt(), titleTopPx.roundToInt()) }
            .width(pxToDp(textWidthPx, density)),
    )
    if (track.artist.isNotBlank()) {
        BasicText(
            text = track.artist,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.62f),
                fontSize = with(density) { artistSizePx.toSp() },
                fontFamily = fonts.regular,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset { IntOffset(textLeftPx.roundToInt(), artistTopPx.roundToInt()) }
                .width(pxToDp(textWidthPx, density)),
        )
    }
}

@Composable
private fun MenuButton(port: LyricsPlayerPort, modifier: Modifier) {
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "更多",
            ) {
                if (!port.more()) visible = false
            }
            .semantics { contentDescription = "更多" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { drawGlyph(PlayerGlyph.More, Color.White.copy(alpha = 0.8f)) }
    }
}

@Composable
private fun ExitButton(port: LyricsPlayerPort, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "退出歌词页",
            ) {
                if (!port.closePage()) Log.w(LOG_TAG, "closePage refused by host")
            }
            .semantics { contentDescription = "退出歌词页" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { drawGlyph(PlayerGlyph.ChevronDown, Color.White.copy(alpha = 0.9f)) }
    }
}

@Composable
private fun ProgressSeekBar(
    port: LyricsPlayerPort,
    runtime: LyricsPlayerRuntime,
    preview: SeekPreview,
    widthPx: Float,
    barHeightPx: Float,
    thumbRadiusPx: Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val durationMs = runtime.durationMsState.value
    val touchHeightPx = max(barHeightPx * 6f, dpValueToPx(BAR_TOUCH_DP, density))
    Canvas(
        modifier
            .width(pxToDp(widthPx, density))
            .height(pxToDp(touchHeightPx, density))
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun fractionAt(x: Float): Float =
                        (x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    preview.fraction = fractionAt(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            if (durationMs > 0L) {
                                port.seekTo(((preview.fraction ?: 0f) * durationMs.toFloat()).toLong())
                            }
                            preview.fraction = null
                            change.consume()
                            break
                        }
                        preview.fraction = fractionAt(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        runtime.tick.value
        val duration = durationMs.coerceAtLeast(0L)
        val positionMs = preview.fraction?.let { (it * duration).toLong() } ?: runtime.clock.visualPositionMs()
        val fraction = if (duration > 0L) {
            (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val centerY = size.height * 0.5f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.24f),
            topLeft = Offset(0f, centerY - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx * 0.5f),
        )
        if (fraction > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.76f),
                topLeft = Offset(0f, centerY - barHeightPx * 0.5f),
                size = Size(size.width * fraction, barHeightPx),
                cornerRadius = CornerRadius(barHeightPx * 0.5f),
            )
        }
        val radius = thumbRadiusPx
        val thumbX = if (size.width <= radius * 2f) {
            size.width * 0.5f
        } else {
            (size.width * fraction).coerceIn(radius, size.width - radius)
        }
        drawCircle(color = Color.White.copy(alpha = 0.76f), radius = radius, center = Offset(thumbX, centerY))
    }
}

@Composable
private fun TimesRow(
    runtime: LyricsPlayerRuntime,
    preview: SeekPreview,
    timeSizePx: Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val durationMs = runtime.durationMsState.value
    val elapsedMs = runtime.elapsedSecondsState.value * 1000L
    val shownMs = preview.fraction?.let { (it * durationMs).toLong() } ?: elapsedMs
    val remainingMs = (durationMs - shownMs).coerceAtLeast(0L)
    val style = TextStyle(
        color = Color.White.copy(alpha = 0.55f),
        fontSize = with(density) { timeSizePx.toSp() },
    )
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(LyricsPlayerFormat.position(shownMs), style = style, maxLines = 1)
        BasicText("-" + LyricsPlayerFormat.position(remainingMs), style = style, maxLines = 1)
    }
}

@Composable
private fun TransportRow(
    port: LyricsPlayerPort,
    playing: Boolean,
    columns: LyricsPlayerColumns,
    modifier: Modifier,
    expanded: Boolean,
) {
    val density = LocalDensity.current
    var previousEnabled by remember { mutableStateOf(true) }
    var playEnabled by remember { mutableStateOf(true) }
    var nextEnabled by remember { mutableStateOf(true) }
    val iconSize = columns.transportIconPx
    val playSize = columns.playIconPx
    if (!expanded) {
        Row(
            modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportIcon(PlayerGlyph.Previous, iconSize, previousEnabled, density) {
                if (!port.skipPrevious()) previousEnabled = false
            }
            Spacer(Modifier.width(pxToDp(iconSize * 1.6f, density)))
            TransportIcon(
                glyph = if (playing) PlayerGlyph.Pause else PlayerGlyph.Play,
                sizePx = playSize,
                enabled = playEnabled,
                density = density,
            ) { if (!port.playPause()) playEnabled = false }
            Spacer(Modifier.width(pxToDp(iconSize * 1.6f, density)))
            TransportIcon(PlayerGlyph.Next, iconSize, nextEnabled, density) {
                if (!port.skipNext()) nextEnabled = false
            }
        }
        return
    }
    // Tablet reference: shuffle, prev, play, next, repeat, spread across the cover column.
    Box(modifier) {
        TransportIcon(
            glyph = PlayerGlyph.Shuffle,
            sizePx = iconSize * 0.8f,
            enabled = false,
            density = density,
            dimAlpha = 0.62f,
            fraction = 0.042f,
            columnWidthPx = columns.leftColumnWidthPx,
        )
        TransportIcon(PlayerGlyph.Previous, iconSize, previousEnabled, density, fraction = 0.229f, columnWidthPx = columns.leftColumnWidthPx, rowHeightPx = columns.playIconPx) {
            if (!port.skipPrevious()) previousEnabled = false
        }
        TransportIcon(
            glyph = if (playing) PlayerGlyph.Pause else PlayerGlyph.Play,
            sizePx = playSize,
            enabled = playEnabled,
            density = density,
            fraction = 0.375f,
            columnWidthPx = columns.leftColumnWidthPx,
            rowHeightPx = columns.playIconPx,
        ) { if (!port.playPause()) playEnabled = false }
        TransportIcon(PlayerGlyph.Next, iconSize, nextEnabled, density, fraction = 0.525f, columnWidthPx = columns.leftColumnWidthPx, rowHeightPx = columns.playIconPx) {
            if (!port.skipNext()) nextEnabled = false
        }
        TransportIcon(
            glyph = PlayerGlyph.Repeat,
            sizePx = iconSize * 0.8f,
            enabled = false,
            density = density,
            dimAlpha = 0.62f,
            fraction = 0.985f,
            columnWidthPx = columns.leftColumnWidthPx,
        )
    }
}

@Composable
private fun TransportIcon(
    glyph: PlayerGlyph,
    sizePx: Float,
    enabled: Boolean,
    density: Density,
    dimAlpha: Float = if (enabled) 1f else 0.35f,
    fraction: Float = 0f,
    columnWidthPx: Float = 0f,
    rowHeightPx: Float = 0f,
    onClick: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val sizeDp = pxToDp(sizePx, density)
    val clickModifier = if (enabled) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = true,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .offset {
                val x = if (columnWidthPx > 0f) columnWidthPx * fraction - sizePx * 0.5f else 0f
                val y = if (rowHeightPx > 0f) (rowHeightPx - sizePx) * 0.5f else 0f
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .size(sizeDp)
            .clip(CircleShape)
            .then(clickModifier)
            .semantics { contentDescription = glyph.name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawGlyph(glyph, Color.White.copy(alpha = dimAlpha))
        }
    }
}

@Composable
private fun VolumeRow(columns: LyricsPlayerColumns, modifier: Modifier) {
    val density = LocalDensity.current
    val heightPx = columns.volumeBarHeightPx * 4f
    Box(modifier.height(pxToDp(heightPx, density))) {
        Canvas(
            Modifier
                .offset { IntOffset(dpValueToPx(SPEAKER_LEFT_DP, density).roundToInt(), 0) }
                .size(pxToDp(columns.transportIconPx * 0.7f, density)),
        ) {
            drawGlyph(PlayerGlyph.VolumeLow, Color.White.copy(alpha = 0.30f))
        }
        Canvas(
            Modifier
                .offset {
                    IntOffset(
                        (columns.leftColumnWidthPx * 0.049f).roundToInt(),
                        (heightPx * 0.5f - columns.volumeBarHeightPx * 0.5f).roundToInt(),
                    )
                }
                .width(pxToDp(columns.leftColumnWidthPx * 0.867f, density))
                .height(pxToDp(columns.volumeBarHeightPx, density)),
        ) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.23f),
                cornerRadius = CornerRadius(columns.volumeBarHeightPx * 0.5f),
            )
        }
        Canvas(
            Modifier
                .offset {
                    IntOffset(
                        (columns.leftColumnWidthPx * 0.93f).roundToInt(),
                        (heightPx * 0.5f - columns.transportIconPx * 0.35f).roundToInt(),
                    )
                }
                .size(pxToDp(columns.transportIconPx * 0.7f, density)),
        ) {
            drawGlyph(PlayerGlyph.VolumeHigh, Color.White.copy(alpha = 0.30f))
        }
    }
}

@Composable
private fun FooterRow(columns: LyricsPlayerColumns, fonts: LyricsPlayerFonts, modifier: Modifier) {
    val density = LocalDensity.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(pxToDp(columns.footerFontPx, density))) {
            drawCircle(Color.White.copy(alpha = 0.35f), radius = size.minDimension * 0.5f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            drawCircle(Color.White.copy(alpha = 0.35f), radius = size.minDimension * 0.18f)
        }
        Spacer(Modifier.width(pxToDp(columns.footerFontPx * 0.55f, density)))
        BasicText(
            text = "LyricsBlossom by Eplor with ♡",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.42f),
                fontSize = with(density) { columns.footerFontPx.toSp() },
                fontFamily = fonts.regular,
            ),
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Background
// ---------------------------------------------------------------------------------------

@Composable
private fun LyricsPlayerBackground(
    artwork: ImageBitmap?,
    viewportWidthPx: Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val mesh = rememberMeshArtwork(artwork)
    // RE: the reference background is a multi-colour block field, not a blurred cover:
    // cover -> 6..10 colour blocks -> smooth upscale -> gaussian sigma about 2.4 % of the
    // width -> black scrim (alpha 0.25-0.40). No linear gradient is laid over it.
    val blurRadius = with(density) { (viewportWidthPx * BACKGROUND_BLUR_WIDTH_FRACTION).toDp() }
    Box(modifier.background(FALLBACK_BOTTOM)) {
        if (mesh != null) {
            Image(
                bitmap = mesh,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKGROUND_SCRIM_ALPHA)))
    }
}

/**
 * Reduces the cover to a [MESH_SIZE] x [MESH_SIZE] colour field and lets the image scaler
 * interpolate it back to full size: the block-mesh background of the reference page.
 */
@Composable
private fun rememberMeshArtwork(artwork: ImageBitmap?): ImageBitmap? = remember(artwork) {
    if (artwork == null) {
        null
    } else {
        runCatching {
            val source = artwork.asAndroidBitmap()
            Bitmap.createScaledBitmap(source, MESH_SIZE, MESH_SIZE, true).asImageBitmap()
        }.getOrNull()
    }
}

// ---------------------------------------------------------------------------------------
// Units and constants
// ---------------------------------------------------------------------------------------

private fun pxToDp(px: Float, density: Density): Dp = with(density) { px.toDp() }

private fun pxToPx(value: Dp, density: Density): Float = with(density) { value.toPx() }

private fun dpValueToPx(value: Float, density: Density): Float = with(density) { value.dp.toPx() }

private const val LOG_TAG = "lyrics player"
private const val BAR_TOUCH_DP = 30f
private const val SPEAKER_LEFT_DP = 2f

/** RE: background is a 6-10 block colour field, upscaled and blurred by ~2.4 % of the width. */
private const val MESH_SIZE = 8
private const val BACKGROUND_BLUR_WIDTH_FRACTION = 0.024f
private const val BACKGROUND_SCRIM_ALPHA = 0.30f
private val FALLBACK_BOTTOM = Color(0xFF05060A.toInt())
