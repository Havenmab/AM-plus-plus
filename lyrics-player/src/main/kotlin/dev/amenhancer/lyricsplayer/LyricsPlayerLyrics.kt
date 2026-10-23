package dev.amenhancer.lyricsplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.lyrics.player.LyricsPlayerLayout
import dev.amenhancer.module.lyrics.player.LyricsPlayerMotion
import dev.amenhancer.module.lyrics.player.LyricsPlayerTimeline
import dev.amenhancer.module.lyrics.player.PlayerDocument
import dev.amenhancer.module.lyrics.player.PlayerLine
import dev.amenhancer.module.lyrics.player.WordHighlight
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Text layouts of one lyric line, measured exactly once per document/width/font change. */
private class MeasuredRow(
    val main: TextLayoutResult,
    val translation: TextLayoutResult?,
    val harmony: TextLayoutResult?,
    val wordStartX: FloatArray,
    val wordEndX: FloatArray,
) {
    /** Pixel x of the karaoke fill edge inside the line's text layout. */
    fun fillX(highlight: WordHighlight): Float {
        val index = highlight.wordIndex
        if (index < 0 || index >= wordStartX.size) return 0f
        val start = wordStartX[index]
        val end = wordEndX[index]
        return start + (end - start) * highlight.wordProgress.coerceIn(0f, 1f)
    }

    /** Left edge of word [index] inside the line's text layout. */
    fun wordStart(index: Int): Float =
        if (index < 0 || index >= wordStartX.size) 0f else wordStartX[index]

    /** Right edge of word [index] inside the line's text layout. */
    fun wordEnd(index: Int): Float =
        if (index < 0 || index >= wordEndX.size) 0f else wordEndX[index]

    /**
     * Bright text up to the fill edge, feathered over [featherPx], dim afterwards. Created
     * once per frame for the current line only; everything else is drawn with a plain colour.
     */
    fun gradientBrush(widthPx: Float, fillX: Float, featherPx: Float): Brush {
        val width = widthPx.coerceAtLeast(1f)
        val edge = (fillX - featherPx).coerceAtLeast(0f) / width
        val edgeStop = edge.coerceIn(0f, 1f)
        val fillStop = (fillX / width).coerceIn(edgeStop, 1f)
        return Brush.linearGradient(
            edgeStop to BRIGHT_WHITE,
            fillStop to DIM_WHITE,
            start = Offset.Zero,
            end = Offset(width, 0f),
        )
    }
}

private class MeasuredLyrics(val rows: List<MeasuredRow>) {
    companion object {
        val EMPTY = MeasuredLyrics(emptyList())
    }
}

/**
 * Self-drawn lyrics area: vertical scroll with 1:1 drag, fling, settle and tap-to-seek.
 *
 * Text is measured once per document identity / width / font size; the frame loop only
 * moves numbers. Nothing in the draw path allocates a text layout.
 */
@Composable
internal fun LyricsPlayerLyrics(
    runtime: LyricsPlayerRuntime,
    windowHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val textMeasurer = rememberTextMeasurer()
    val config = runtime.configState.value
    val document = runtime.documentState.value
    val geometry = runtime.geometryState.value

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val layout = remember(
            widthPx,
            heightPx,
            windowHeightPx,
            density,
            config.fontScalePercent,
            isTablet,
            geometry,
        ) {
            LyricsPlayerLayout(
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx,
                windowHeightPx = windowHeightPx,
                density = density.density,
                isTablet = isTablet,
                fontScalePercent = config.fontScalePercent,
                // Host hints are pixels in the overlay space; zero means "not measured".
                hostTextSizePx = geometry?.textSizePx ?: 0f,
                hostLineSpacingPx = geometry?.lineSpacingPx ?: 0f,
                hostHorizontalInsetPx = geometry?.horizontalInsetPx ?: 0f,
                hostContentWidthPx = geometry?.widthPx ?: 0f,
            )
        }
        val mainStyle = remember(layout.fontSizePx, layout.lineHeightPx, density) {
            TextStyle(
                fontSize = with(density) { layout.fontSizePx.toSp() },
                lineHeight = with(density) { layout.lineHeightPx.toSp() },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        val secondarySize = with(density) { (layout.fontSizePx * SECONDARY_TEXT_RATIO).toSp() }
        val translationStyle = remember(secondarySize) {
            TextStyle(fontSize = secondarySize, textAlign = TextAlign.Center)
        }
        val harmonySize = with(density) { (layout.fontSizePx * SECONDARY_TEXT_RATIO * 0.9f).toSp() }
        val harmonyStyle = remember(harmonySize) {
            TextStyle(fontSize = harmonySize, textAlign = TextAlign.Center)
        }
        val measured = remember(
            document?.identity,
            layout.contentWidthPx,
            layout.fontSizePx,
            textMeasurer,
            mainStyle,
            translationStyle,
            harmonyStyle,
        ) {
            measureDocument(document, textMeasurer, layout, mainStyle, translationStyle, harmonyStyle)
        }
        val emptyText = remember(textMeasurer, layout.fontSizePx, density) {
            textMeasurer.measure(
                text = AnnotatedString(EMPTY_TEXT),
                style = TextStyle(
                    fontSize = LyricsPlayerChromeSpec.EMPTY_TEXT_SIZE,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
        val glowShadow = remember(density) {
            Shadow(
                color = Color.White.copy(alpha = GLOW_ALPHA),
                blurRadius = with(density) { LyricsPlayerMotion.GLOW_DP.dp.toPx() },
            )
        }
        // RE: A level — Specs +0x54 = 40.0 (the iOS notes said 30).
        val featherPx = with(density) { LyricsPlayerMotion.FEATHER_DP.dp.toPx() }
        val syllableLiftPx = with(density) { LyricsPlayerMotion.SYLLABLE_LIFT_DP.dp.toPx() }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(layout, runtime, document?.identity) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDragY = 0f
                        var dragging = false
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                if (dragging) {
                                    runtime.endDrag(
                                        velocityY = velocityTracker.calculateVelocity().y,
                                        linePitchPx = layout.linePitchPx,
                                        nowNanos = runtime.lastFrameNanos,
                                    )
                                } else {
                                    runtime.tapAt(down.position.y, layout, runtime.lastFrameNanos)
                                }
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            totalDragY += delta.y
                            if (!dragging && abs(totalDragY) > viewConfiguration.touchSlop) {
                                dragging = true
                                runtime.beginDrag()
                            }
                            if (dragging) {
                                runtime.dragBy(delta.y, layout.linePitchPx)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            val nowNanos = runtime.tick.value
            if (measured.rows.isEmpty()) {
                val breath = 0.30f + 0.18f * sin(nowNanos / 1_000_000_000.0 * BREATH_SPEED).toFloat()
                drawEmptyLyrics(emptyText, layout, breath)
                return@Canvas
            }
            drawLyrics(measured, layout, runtime, nowNanos, featherPx, glowShadow, syllableLiftPx)
        }
    }
}

private fun DrawScope.drawEmptyLyrics(emptyText: TextLayoutResult, layout: LyricsPlayerLayout, alpha: Float) {
    val topLeft = Offset(
        x = (size.width - emptyText.size.width) * 0.5f,
        y = layout.anchorYPx - emptyText.size.height * 0.5f,
    )
    drawText(emptyText, Color.White.copy(alpha = alpha), topLeft)
}

private fun DrawScope.drawLyrics(
    measured: MeasuredLyrics,
    layout: LyricsPlayerLayout,
    runtime: LyricsPlayerRuntime,
    nowNanos: Long,
    featherPx: Float,
    glowShadow: Shadow,
    syllableLiftPx: Float,
) {
    val lines = runtime.document?.lines.orEmpty()
    // A document can change between the frame loop and the next recomposition; the measured
    // rows and the line list are only guaranteed to agree up to the smaller of the two.
    val lineCount = minOf(lines.size, measured.rows.size)
    if (lineCount == 0) return
    val currentIndex = runtime.currentIndex.coerceIn(0, lineCount - 1)
    val positionMs = runtime.clock.visualPositionMs()
    val elapsedMs = ((nowNanos - runtime.lineChangeNanos) / 1_000_000L).coerceAtLeast(0L)
    val config = runtime.config
    val currentLine = lines[currentIndex]
    val currentHighlight = LyricsPlayerTimeline.wordHighlight(currentLine, positionMs)
    val currentProgress = LyricsPlayerTimeline.lineProgress(currentLine, positionMs)
    val range = layout.visibleLineRange(runtime.displayedIndex, lineCount)

    for (index in range) {
        if (index == currentIndex) continue
        drawLine(
            index, measured.rows[index], layout, runtime, config, elapsedMs,
            isCurrent = false, highlight = WordHighlight.NONE, lineProgress = 0f,
            featherPx = featherPx, glowShadow = glowShadow, syllableLiftPx = syllableLiftPx,
        )
    }
    // The current line is painted last so its glow sits above its neighbours.
    drawLine(
        currentIndex, measured.rows[currentIndex], layout, runtime, config, elapsedMs,
        isCurrent = true, highlight = currentHighlight, lineProgress = currentProgress,
        featherPx = featherPx, glowShadow = glowShadow, syllableLiftPx = syllableLiftPx,
    )
}

private fun DrawScope.drawLine(
    index: Int,
    row: MeasuredRow,
    layout: LyricsPlayerLayout,
    runtime: LyricsPlayerRuntime,
    config: LyricsPlayerConfig,
    elapsedMs: Long,
    isCurrent: Boolean,
    highlight: WordHighlight,
    lineProgress: Float,
    featherPx: Float,
    glowShadow: Shadow,
    syllableLiftPx: Float,
) {
    val centerY = layout.lineCenterY(index, runtime.displayedIndex)
    if (centerY < -layout.lineHeightPx || centerY > size.height + layout.lineHeightPx) return
    val emphasis = LyricsPlayerMotion.lineEmphasis(
        elapsedMs = elapsedMs,
        lineIndex = index,
        currentIndex = runtime.currentIndex,
        previousIndex = runtime.previousIndex,
        staggerMs = config.staggerMs,
        motionPercent = config.motionPercent,
    )
    val progress = when {
        isCurrent -> lineProgress
        index < runtime.currentIndex -> 1f
        else -> 0f
    }
    val scale = LyricsPlayerMotion.lineScale(emphasis, progress, config.emphasisPercent) *
        runtime.tapBoost(index, runtime.lastFrameNanos)
    val main = row.main
    val textWidth = main.size.width.toFloat()
    val textHeight = main.size.height.toFloat()
    val topLeft = Offset((size.width - textWidth) * 0.5f, centerY - textHeight * 0.5f)
    withTransform({ scale(scale, scale, Offset(size.width * 0.5f, centerY)) }) {
        if (isCurrent && highlight.hasWords) {
            val fillX = row.fillX(highlight).coerceIn(0f, textWidth)
            if (fillX > 0f) {
                // The shadow makes the sung part glow; the dim, not-yet-sung tail keeps none.
                clipRect(right = fillX, top = 0f, bottom = size.height) {
                    drawText(main, Color.White.copy(alpha = GLOW_TEXT_ALPHA), topLeft, shadow = glowShadow)
                }
                val brush = row.gradientBrush(textWidth, fillX, featherPx)
                val liftedStart = row.wordStart(highlight.wordIndex)
                val liftedEnd = fillX
                if (liftedEnd - liftedStart <= 0.5f) {
                    // Nothing sung inside the current word yet: the whole line stays put.
                    drawText(main, brush, topLeft)
                } else {
                    // Everything except the syllable being sung stays on the baseline…
                    if (liftedStart > 0.5f) {
                        clipRect(right = liftedStart, top = 0f, bottom = size.height) {
                            drawText(main, brush, topLeft)
                        }
                    }
                    if (fillX < textWidth - 0.5f) {
                        clipRect(left = fillX, top = 0f, bottom = size.height) {
                            drawText(main, brush, topLeft)
                        }
                    }
                    // …and the sung part of the current word floats up by the syllable lift (§5.2).
                    clipRect(
                        left = liftedStart,
                        top = -syllableLiftPx,
                        right = liftedEnd,
                        bottom = size.height,
                    ) {
                        drawText(main, brush, topLeft + Offset(0f, -syllableLiftPx))
                    }
                }
            } else {
                drawText(main, DIM_WHITE, topLeft)
            }
        } else {
            val alpha = if (isCurrent) CURRENT_LINE_NO_WORDS_ALPHA
            else LyricsPlayerMotion.lineWhite(index, runtime.currentIndex)
            drawText(main, Color.White.copy(alpha = alpha), topLeft)
        }
    }
    val secondaryTop = centerY + textHeight * 0.5f
    row.translation?.let { translation ->
        drawText(
            translation,
            Color.White.copy(alpha = TRANSLATION_ALPHA),
            Offset((size.width - translation.size.width) * 0.5f, secondaryTop),
        )
    }
    row.harmony?.let { harmony ->
        drawText(
            harmony,
            Color.White.copy(alpha = HARMONY_ALPHA),
            Offset(
                (size.width - harmony.size.width) * 0.5f,
                secondaryTop + harmony.size.height * 0.85f,
            ),
        )
    }
}

private fun measureDocument(
    document: PlayerDocument?,
    textMeasurer: TextMeasurer,
    layout: LyricsPlayerLayout,
    mainStyle: TextStyle,
    translationStyle: TextStyle,
    harmonyStyle: TextStyle,
): MeasuredLyrics {
    val lines = document?.lines ?: return MeasuredLyrics.EMPTY
    if (lines.isEmpty()) return MeasuredLyrics.EMPTY
    val constraints = Constraints(maxWidth = layout.contentWidthPx.roundToInt())
    val rows = ArrayList<MeasuredRow>(lines.size)
    for (line in lines) {
        val main = textMeasurer.measure(
            text = AnnotatedString(line.text),
            style = mainStyle,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = MAX_LINE_ROWS,
            constraints = constraints,
        )
        val (wordStarts, wordEnds) = wordEdges(main, line)
        val translation = line.translation.trim().takeIf { it.isNotEmpty() }?.let { text ->
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = translationStyle,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
                maxLines = 1,
                constraints = constraints,
            )
        }
        val harmonyText = line.backgroundWords.joinToString(separator = " ") { word -> word.text }.trim()
        val harmony = harmonyText.takeIf { it.isNotEmpty() && it != line.text }?.let { text ->
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = harmonyStyle,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
                maxLines = 1,
                constraints = constraints,
            )
        }
        rows.add(MeasuredRow(main, translation, harmony, wordStarts, wordEnds))
    }
    return MeasuredLyrics(rows)
}

/**
 * Horizontal x of every word start/end inside [main]. The host's line text and the timed
 * word breakdown normally concatenate exactly; when they do not, the cursor fallback keeps
 * the fill monotonic instead of jumping backwards.
 */
private fun wordEdges(main: TextLayoutResult, line: PlayerLine): Pair<FloatArray, FloatArray> {
    val words = line.words
    if (words.isEmpty()) return EMPTY_EDGES to EMPTY_EDGES
    val starts = FloatArray(words.size)
    val ends = FloatArray(words.size)
    val text = line.text
    var cursor = 0
    var lastX = 0f
    for (i in words.indices) {
        val word = words[i]
        val located = text.indexOf(word.text, cursor)
        val start = if (located >= 0) located else cursor.coerceIn(0, text.length)
        val end = (start + word.text.length).coerceIn(start, text.length)
        val startX = main.getHorizontalPosition(start, usePrimaryDirection = true).coerceAtLeast(lastX)
        val endX = main.getHorizontalPosition(end, usePrimaryDirection = true).coerceAtLeast(startX)
        starts[i] = startX
        ends[i] = endX
        lastX = endX
        cursor = end
    }
    return starts to ends
}

private val EMPTY_EDGES = FloatArray(0)
private val BRIGHT_WHITE = Color.White
private val DIM_WHITE = Color.White.copy(alpha = 0.30f)
private val GLOW_ALPHA = 0.26f
private const val GLOW_TEXT_ALPHA = 0.65f
private const val TRANSLATION_ALPHA = 0.42f
private const val HARMONY_ALPHA = 0.26f
private const val CURRENT_LINE_NO_WORDS_ALPHA = 0.90f
private const val SECONDARY_TEXT_RATIO = 0.40f
private const val MAX_LINE_ROWS = 2
private const val TABLET_MIN_WIDTH_DP = 600
private const val BREATH_SPEED = 2.6
private const val EMPTY_TEXT = "暂无歌词"
