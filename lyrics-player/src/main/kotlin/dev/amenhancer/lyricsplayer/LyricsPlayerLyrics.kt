package dev.amenhancer.lyricsplayer

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.amenhancer.module.lyrics.player.LyricsPlayerColumns
import dev.amenhancer.module.lyrics.player.LyricsPlayerTimeline
import dev.amenhancer.module.lyrics.player.PlayerDocument
import dev.amenhancer.module.lyrics.player.PlayerLine
import dev.amenhancer.module.lyrics.player.WordHighlight
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Two text layouts per line: bold/current and regular/neighbour, plus the translation. */
private class MeasuredRow(
    val current: TextLayoutResult,
    val other: TextLayoutResult,
    val translation: TextLayoutResult?,
    val wordStartX: FloatArray,
    val wordEndX: FloatArray,
) {
    fun wordStart(index: Int): Float =
        if (index < 0 || index >= wordStartX.size) 0f else wordStartX[index]

    fun wordEnd(index: Int): Float =
        if (index < 0 || index >= wordEndX.size) 0f else wordEndX[index]

    /** Pixel x of the karaoke fill edge inside the line's layout. */
    fun fillX(highlight: WordHighlight): Float {
        val index = highlight.wordIndex
        if (index < 0 || index >= wordStartX.size) return 0f
        val start = wordStartX[index]
        val end = wordEndX[index]
        return start + (end - start) * highlight.wordProgress.coerceIn(0f, 1f)
    }

    /** Bright text up to the fill edge, feathered over [featherPx], dim afterwards. */
    fun gradientBrush(widthPx: Float, fillX: Float, featherPx: Float): Brush {
        val width = widthPx.coerceAtLeast(1f)
        val edgeStop = ((fillX - featherPx).coerceAtLeast(0f) / width).coerceIn(0f, 1f)
        val fillStop = (fillX / width).coerceIn(edgeStop, 1f)
        return Brush.linearGradient(
            edgeStop to Color.White,
            fillStop to Color.White.copy(alpha = 0.30f),
            start = Offset.Zero,
            end = Offset(width, 0f),
        )
    }
}

private class LyricsStyles(
    val current: TextStyle,
    val other: TextStyle,
    val translation: TextStyle,
)

/**
 * Self-drawn lyrics column (v2): every line is its own composable so the neighbours can
 * carry a real Gaussian blur layer (RE: VISUAL-SPEC - the fade comes from the blur, the
 * painted alpha stays constant at 0.50). Only the lines around the current one are composed;
 * their vertical position is animated through graphicsLayer translations, so scrolling does
 * not recompose or re-measure anything.
 */
@Composable
internal fun LyricsPlayerLyrics(
    runtime: LyricsPlayerRuntime,
    columns: LyricsPlayerColumns,
    fonts: LyricsPlayerFonts,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val document = runtime.documentState.value
    val styles = remember(columns.currentFontPx, columns.otherFontPx, columns.translationFontPx, fonts) {
        buildStyles(columns, fonts, density)
    }
    val rows = remember(document?.identity, columns.lyricsWidthPx, styles) {
        measureDocument(document, textMeasurer, columns, styles)
    }
    val currentIndex = runtime.currentIndexState.value
    val lineCount = min(rows.size, document?.lines?.size ?: 0)
    val emptyLayout = remember(textMeasurer, styles, density) {
        textMeasurer.measure(AnnotatedString(EMPTY_TEXT), styles.other, TextOverflow.Clip, true, 1)
    }
    // One blur layer per distance tier, created once (never per frame). The current line has none.
    val blurEffects = remember(columns) {
        val effects = arrayOfNulls<BlurEffect>(BLUR_TIERS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (distance in 1 until BLUR_TIERS) {
                val sigma = columns.blurSigmaPx(distance)
                if (sigma > 0f) effects[distance] = BlurEffect(sigma, sigma, TileMode.Clamp)
            }
        }
        effects
    }
    val featherPx = columns.currentFontPx * FEATHER_RATIO

    Box(
        modifier
            .clipToBounds()
            .pointerInput(columns, runtime, document?.identity) {
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
                                    linePitchPx = columns.linePitchPx,
                                    nowNanos = runtime.lastFrameNanos,
                                )
                            } else {
                                runtime.tapAt(down.position.y, columns, runtime.lastFrameNanos)
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
                            runtime.dragBy(delta.y, columns.linePitchPx)
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        if (lineCount == 0) {
            Canvas(Modifier.fillMaxSize()) {
                val breath = 0.32f + 0.16f * sin(runtime.tick.value / 1_000_000_000.0 * BREATH_SPEED).toFloat()
                drawText(
                    emptyLayout,
                    Color.White.copy(alpha = breath),
                    Offset(0f, columns.anchorYPx - columns.lyricsTopPx - emptyLayout.size.height * 0.5f),
                )
            }
            return@Box
        }
        val first = (currentIndex - LINE_OVERSCAN).coerceIn(0, lineCount - 1)
        val last = (currentIndex + LINE_OVERSCAN).coerceIn(0, lineCount - 1)
        for (index in first..last) {
            val line = document?.lines?.getOrNull(index) ?: continue
            LyricLineView(
                index = index,
                row = rows[index],
                line = line,
                currentIndex = currentIndex,
                runtime = runtime,
                columns = columns,
                styles = styles,
                blurEffect = blurEffects[abs(index - currentIndex).coerceAtMost(BLUR_TIERS - 1)],
                featherPx = featherPx,
                density = density,
            )
        }
    }
}

@Composable
private fun LyricLineView(
    index: Int,
    row: MeasuredRow,
    line: PlayerLine,
    currentIndex: Int,
    runtime: LyricsPlayerRuntime,
    columns: LyricsPlayerColumns,
    styles: LyricsStyles,
    blurEffect: BlurEffect?,
    featherPx: Float,
    density: Density,
) {
    val distance = abs(index - currentIndex)
    val slotHeightPx = columns.linePitchPx * SLOT_RATIO
    val slotHeightDp = with(density) { slotHeightPx.toDp() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(slotHeightDp)
            .zIndex(if (distance == 0) 1f else 0f)
            .graphicsLayer {
                // One tick read per line keeps the layer updating while every line runs its
                // own spring; the values themselves are plain floats (no recomposition).
                runtime.tick.value
                val displayed = runtime.lineShift(index)
                val center = columns.lineCenterY(index, displayed) - columns.lyricsTopPx
                translationY = center - slotHeightPx * 0.5f
                // RE §12: the per-line scale spring (0.98 deselected -> 1.0 current).
                val lineScale = runtime.lineScale(index)
                scaleX = lineScale
                scaleY = lineScale
                // RE: task-8 §6 — renderLine paints with clamp(progress, 0, 1) and culls
                // below 2/255 (0.007843); progress is not used for y/scale.
                val progress = runtime.lineAlpha(index).coerceIn(0f, 1f)
                alpha = if (progress < MIN_LINE_ALPHA) 0f else progress
                clip = false
                renderEffect = blurEffect
            },
    ) {
        // Only the current line changes inside the canvas (word fill); neighbours repaint
        // only when the frame clock or the composition says so.
        if (distance == 0) runtime.tick.value
        val mainTop = slotHeightPx * 0.5f - row.current.size.height * 0.5f
        if (distance == 0) {
            drawCurrentLine(
                row = row,
                line = line,
                topLeft = Offset(0f, mainTop),
                widthPx = columns.lyricsWidthPx,
                featherPx = featherPx,
                positionMs = runtime.clock.visualPositionMs(),
            )
        } else {
            drawText(row.other, Color.White, Offset(0f, mainTop))
        }
        row.translation?.let { translation ->
            val translationTop = slotHeightPx * 0.5f +
                columns.translationCenterOffsetPx - translation.size.height * 0.5f
            // The layer alpha is the line progress; the translation keeps its relative 0.30.
            drawText(
                translation,
                Color.White.copy(alpha = columns.translationAlpha),
                Offset(0f, translationTop),
            )
        }
    }
}

private fun DrawScope.drawCurrentLine(
    row: MeasuredRow,
    line: PlayerLine,
    topLeft: Offset,
    widthPx: Float,
    featherPx: Float,
    positionMs: Long,
) {
    val highlight = LyricsPlayerTimeline.wordHighlight(line, positionMs)
    if (!highlight.hasWords) {
        drawText(row.current, Color.White, topLeft)
        return
    }
    val textWidth = row.current.size.width.toFloat().coerceAtMost(widthPx)
    val fillX = row.fillX(highlight).coerceIn(0f, textWidth)
    if (fillX <= 0f) {
        drawText(row.current, Color.White.copy(alpha = 0.30f), topLeft)
        return
    }
    // Unsung remainder stays dim, the sung part is pure white with a feathered edge.
    drawText(row.current, Color.White.copy(alpha = 0.30f), topLeft)
    drawText(row.current, row.gradientBrush(textWidth, fillX, featherPx), topLeft)
}

private fun buildStyles(
    columns: LyricsPlayerColumns,
    fonts: LyricsPlayerFonts,
    density: Density,
): LyricsStyles {
    fun style(px: Float, family: androidx.compose.ui.text.font.FontFamily, alpha: Float = 1f): TextStyle =
        TextStyle(
            color = Color.White.copy(alpha = alpha),
            fontSize = with(density) { px.toSp() },
            lineHeight = with(density) { (px * 1.2f).toSp() },
            fontFamily = family,
            textAlign = TextAlign.Start,
        )
    return LyricsStyles(
        current = style(columns.currentFontPx, fonts.bold),
        other = style(columns.otherFontPx, fonts.regular),
        translation = style(columns.translationFontPx, fonts.medium, alpha = columns.translationAlpha),
    )
}

private fun measureDocument(
    document: PlayerDocument?,
    textMeasurer: TextMeasurer,
    columns: LyricsPlayerColumns,
    styles: LyricsStyles,
): List<MeasuredRow> {
    val lines = document?.lines ?: return emptyList()
    if (lines.isEmpty()) return emptyList()
    // RE: line width = column width * 0.95 (subsidiary rows 0.80 / 0.7); the reference's
    // long lines therefore wrap before the column edge.
    val constraints = Constraints(maxWidth = columns.lineWidthPx.roundToInt().coerceAtLeast(1))
    val rows = ArrayList<MeasuredRow>(lines.size)
    for (line in lines) {
        val current = textMeasurer.measure(
            AnnotatedString(line.text),
            styles.current,
            TextOverflow.Ellipsis,
            true,
            MAX_LINE_ROWS,
            emptyList(),
            constraints,
        )
        val other = textMeasurer.measure(
            AnnotatedString(line.text),
            styles.other,
            TextOverflow.Ellipsis,
            true,
            MAX_LINE_ROWS,
            emptyList(),
            constraints,
        )
        val (wordStarts, wordEnds) = wordEdges(current, line)
        val translation = line.translation.trim().takeIf { it.isNotEmpty() }?.let { text ->
            textMeasurer.measure(
                AnnotatedString(text),
                styles.translation,
                TextOverflow.Ellipsis,
                true,
                1,
                emptyList(),
                constraints,
            )
        }
        rows.add(MeasuredRow(current, other, translation, wordStarts, wordEnds))
    }
    return rows
}

/** Horizontal x of every word start/end inside [main]; monotonic by construction. */
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
private const val EMPTY_TEXT = "暂无歌词"
private const val BREATH_SPEED = 2.2
private const val MAX_LINE_ROWS = 2

/** How many neighbours to compose on each side (covers any tablet viewport with overscan). */
private const val LINE_OVERSCAN = 6

/** Blur tiers: 0 (current) plus four distance tiers, matching the measured sigma curve. */
private const val BLUR_TIERS = 5

/**
 * Slot is taller than the pitch so neither the translation nor the blur bleed can reach the
 * layer bounds, where RenderEffect clipping may leave a faint horizontal seam.
 */
private const val SLOT_RATIO = 2.4f

/** Karaoke feather as a fraction of the current type size (RE: 40 px at 105 px). */
private const val FEATHER_RATIO = 0.38f

/** RE: renderLine's early-out threshold, 0x3C008081 ≈ 2/255. */
private const val MIN_LINE_ALPHA = 0.007843f
