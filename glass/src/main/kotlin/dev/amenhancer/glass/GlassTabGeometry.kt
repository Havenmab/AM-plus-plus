package dev.amenhancer.glass

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cell geometry for the glass tab bar. Coordinates are pixels.
 *
 * By default [contentWidths] is empty and every tab gets an equal share of the panel, which is
 * upstream's behaviour and what the phone bottom bar ([GlassNavigationStyle.Stacked]) keeps.
 *
 * The tablet top bar ([GlassNavigationStyle.TabletLabels]) opts in by passing the measured
 * intrinsic content width of each tab. Each cell is then as wide as its own label (or icon) plus
 * an equal share of the leftover space, so a two-character label sits in a smaller box than a
 * three-character one, an icon-only tab is the narrowest, and the gaps between labels stay equal.
 * A caller that supplies unusable widths (wrong count, non-finite, or wider than the panel) falls
 * back to the equal-cell geometry rather than overflowing.
 */
data class GlassTabGeometry(
    val panelWidth: Float,
    val inset: Float,
    val leadingWidth: Float,
    val count: Int,
    /** AM++: measured intrinsic content (label or glyph) per tab; empty selects equal cells. */
    val contentWidths: List<Float> = emptyList(),
) {
    private val available: Float = panelWidth - 2f * inset - leadingWidth

    /** True when no usable measured content was supplied; upstream's single scalar cell width. */
    val uniform: Boolean = count <= 0 ||
        contentWidths.size != count ||
        !available.isFinite() || available <= 0f ||
        contentWidths.any { !it.isFinite() || it < 0f } ||
        contentWidths.sum() > available

    /** Upstream's equal cell width; also the pill width in the default mode. */
    val tabWidth: Float get() = if (count > 0) (available / count).coerceAtLeast(0f) else 0f

    /** AM++: per-cell widths. Equal to [tabWidth] whenever [uniform]. */
    private val widths: FloatArray = if (uniform) {
        FloatArray(count) { tabWidth }
    } else {
        val extra = (available - contentWidths.sum()) / count
        FloatArray(count) { contentWidths[it] + extra }
    }

    /** Content-relative cell starts; [starts]`[0]` is 0, before the leading action. */
    private val starts: FloatArray = FloatArray(count + 1).also { sums ->
        for (index in 0 until count) sums[index + 1] = sums[index] + widths[index]
    }

    val totalWidth: Float get() = if (count <= 0) 0f else starts[count]

    fun cellWidth(index: Int): Float =
        if (count <= 0) 0f else widths[index.coerceIn(0, count - 1)]

    /**
     * Row weight for a tab. Both modes only need the ratio between cells: the measured widths sum
     * to exactly the space the row has after the leading action, so the weighted layout reproduces
     * [cellWidth] in pixels without a dp round-trip. The default mode stays at the literal 1f the
     * callers used before.
     */
    fun cellWeight(index: Int): Float =
        if (uniform) 1f else cellWidth(index).coerceAtLeast(0.001f)

    /** Content-relative left edge of a continuous tab value; piecewise linear between cells. */
    private fun leftAt(value: Float): Float {
        if (count <= 0) return 0f
        if (count == 1) return 0f
        val clamped = value.coerceIn(0f, (count - 1).toFloat())
        val index = clamped.toInt().coerceAtMost(count - 2)
        return starts[index] + (clamped - index) * widths[index]
    }

    /** Interpolated width of a continuous tab value. */
    private fun widthAt(value: Float): Float {
        if (count <= 0) return 0f
        if (count == 1) return widths[0]
        val clamped = value.coerceIn(0f, (count - 1).toFloat())
        val index = clamped.toInt().coerceAtMost(count - 2)
        return widths[index] + (clamped - index) * (widths[index + 1] - widths[index])
    }

    /** Content-relative cell centre of a continuous tab value; the pill's centre rides this. */
    fun centreAt(value: Float): Float {
        if (count <= 0) return 0f
        if (count == 1) return starts[0] + widths[0] / 2f
        val clamped = value.coerceIn(0f, (count - 1).toFloat())
        val index = clamped.toInt().coerceAtMost(count - 2)
        val from = starts[index] + widths[index] / 2f
        val to = starts[index + 1] + widths[index + 1] / 2f
        return from + (to - from) * (clamped - index)
    }

    /** Pill width of a continuous tab value. */
    fun pillWidthAt(value: Float): Float = if (uniform) tabWidth else widthAt(value)

    /**
     * Pill translation inside the row's content box. The row places the pill at the content start,
     * so the mirrored (RTL) case negates the content-relative left it would have in LTR.
     */
    fun pillTranslation(value: Float, ltr: Boolean): Float {
        if (count <= 0) return 0f
        if (uniform) {
            val left = leadingWidth + value * tabWidth
            return if (ltr) left else -left
        }
        val left = centreAt(value) - widthAt(value) / 2f
        return if (ltr) leadingWidth + left else -leadingWidth - left
    }

    /** Continuous tab value under an absolute pointer x, as the thumb's centre follows a finger. */
    fun valueAtCentre(x: Float): Float {
        if (count <= 0 || !x.isFinite()) return 0f
        if (count == 1) return 0f
        if (x <= centreAt(0f)) return 0f
        if (x >= centreAt((count - 1).toFloat())) return (count - 1).toFloat()
        for (index in 0 until count - 1) {
            val from = centreAt(index.toFloat())
            val to = centreAt((index + 1).toFloat())
            if (x <= to) return index + (x - from) / (to - from)
        }
        return (count - 1).toFloat()
    }

    /** Tab whose cell contains a content-relative x, or null outside the cells. */
    fun indexAtContent(contentX: Float): Int? {
        if (count <= 0) return null
        if (uniform) {
            if (tabWidth <= 0f || !contentX.isFinite()) return null
            if (contentX < 0f || contentX >= tabWidth * count) return null
            return (contentX / tabWidth).toInt().coerceIn(0, count - 1)
        }
        if (!contentX.isFinite() || contentX < 0f || contentX >= totalWidth) return null
        for (index in 0 until count) if (contentX < starts[index + 1]) return index
        return count - 1
    }

    /** Continuous tab value for a content-relative x, used when a press grabs the thumb. */
    fun valueAtContent(contentX: Float): Float {
        if (count <= 0 || !contentX.isFinite()) return 0f
        if (uniform) {
            if (tabWidth <= 0f) return 0f
            return (contentX / tabWidth - 0.5f).coerceIn(0f, (count - 1).toFloat())
        }
        return valueAtCentre(contentX)
    }

    /** Nearest tab centre for a settled continuous value. Equal cells reduce to a plain round. */
    fun snapIndex(value: Float): Int {
        if (count <= 0) return 0
        if (uniform) return value.roundToInt().coerceIn(0, count - 1)
        val x = centreAt(value)
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in 0 until count) {
            val distance = abs(centreAt(index.toFloat()) - x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    fun logicalX(x: Float, ltr: Boolean): Float = (if (ltr) x else panelWidth - x) - inset - leadingWidth

    fun indexAt(x: Float, ltr: Boolean): Int? {
        if (uniform) {
            val logical = logicalX(x, ltr)
            return if (tabWidth <= 0f || logical < 0f || logical >= tabWidth * count) null
            else (logical / tabWidth).toInt().coerceIn(0, count - 1)
        }
        return indexAtContent(logicalX(x, ltr))
    }

    /** LTR offset of a cell's left edge from the content origin, including the leading action. */
    fun thumbOffset(index: Float, ltr: Boolean): Float {
        if (count <= 0) return 0f
        if (uniform) {
            return if (ltr) leadingWidth + index * tabWidth
            else panelWidth - 2f * inset - leadingWidth - (index + 1f) * tabWidth
        }
        val left = leftAt(index)
        return if (ltr) leadingWidth + left else -leadingWidth - left
    }

    /**
     * Continuous tab value after a drag. Equal cells keep upstream's index-space step. Content
     * cells convert through the pill's own x, so the target centre moves by exactly the finger's
     * delta and the pill keeps tracking the finger even when cell widths differ.
     */
    fun draggedIndex(current: Float, deltaX: Float, ltr: Boolean): Float {
        if (uniform) {
            if (!tabWidth.isFinite() || tabWidth <= 0f || !current.isFinite() || !deltaX.isFinite()) return current
            return (current + deltaX / tabWidth * if (ltr) 1f else -1f).coerceIn(0f, (count - 1).toFloat())
        }
        if (count <= 0 || !current.isFinite() || !deltaX.isFinite()) return current
        val centre = centreAt(current)
        return valueAtCentre(centre + if (ltr) deltaX else -deltaX)
    }
}
