package dev.amenhancer.module.hook

import android.os.Build
import android.text.Layout
import android.text.PrecomputedText
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Compensate for edge spacing after span centering, before the row reads the child positions. */
internal fun compensateLyricPronunciationEdges(group: LinearLayout) {
    // A word the model planned out of several mora readings belongs to the splice: it places every
    // one of its fragments itself, from the row's own coordinates. Compensating the same group here
    // would give one reading two writers, and each of them would measure the frame the other wrote.
    // The predicate is derived from the plan, not from what was written earlier, so the order in
    // which the two layout callbacks run cannot matter.
    if (LyricsPronunciationWordAlignment.ownsGroup(group)) return
    if (group.childCount != 2) return
    val top = group.getChildAt(0)
    val bottom = group.getChildAt(1)
    if (top.visibility != View.VISIBLE || bottom.visibility != View.VISIBLE ||
        top.width <= 0 || bottom.width <= 0 || top.width == bottom.width
    ) return

    val anchor = if (top.width > bottom.width) top else bottom
    val moving = if (anchor === top) bottom else top
    val minLeft = group.paddingLeft
    val maxLeft = group.width - group.paddingRight - moving.width
    if (maxLeft <= minLeft) return

    val anchorBias = anchor.edgeBias() ?: return
    val movingBias = moving.edgeBias() ?: return
    val correction = anchorBias - movingBias
    if (correction == 0f) return

    // The centre is recomputed from the layout bounds, so a second pass never adds to the
    // previous offset; the pure arithmetic lives in PronunciationAlignmentMath.
    val left = PronunciationAlignmentMath.centeredLeft(minLeft, maxLeft, correction)
    moving.offsetLeftAndRight(left - moving.left)
}

private fun View.edgeBias(): Float? {
    val pieces = when {
        this is TextView -> listOf(this)
        // Native split glyphs share a horizontal child and move together.
        this is LinearLayout && orientation == LinearLayout.HORIZONTAL ->
            List(childCount) { getChildAt(it) as? TextView ?: return null }
        else -> return null
    }
    val leading = pieces.edgeAdvance(leading = true) ?: return null
    val trailing = pieces.edgeAdvance(leading = false) ?: return null
    val rtl = if (this is TextView) layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        else layoutDirection == View.LAYOUT_DIRECTION_RTL
    // Leading occupies the physical left in LTR; a larger left edge shifts the body right.
    return PronunciationAlignmentMath.edgeBias(leading, trailing, rtl)
}

private fun List<TextView>.edgeAdvance(leading: Boolean): Float? {
    var width = 0f
    for (index in if (leading) indices else indices.reversed()) {
        val piece = this[index]
        val layout = piece.layout ?: return null
        if (piece.visibility != View.VISIBLE || layout.lineCount != 1 || layout.getEllipsisCount(0) != 0) return null
        val text = layout.text
        val start = if (leading) 0 else text.bodyEnd()
        val end = if (leading) text.bodyStart() else text.length
        if (start < end) {
            val direction = layout.getParagraphDirection(0)
            val advance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                text is PrecomputedText && text.params.textPaint.equalsForTextMeasurement(layout.paint)
            ) {
                val rtl = direction == Layout.DIR_RIGHT_TO_LEFT
                if (layout.getLineEnd(0) != text.length || layout.getLineContainsTab(0) ||
                    (maxOf(0, start - 1) until minOf(text.length, end + 1)).any { layout.isRtlCharAt(it) != rtl }
                ) return null
                // Caret queries can return the whole shaped run width at an interior offset.
                text.getWidth(start, end)
            } else {
                val from = layout.getPrimaryHorizontal(start)
                val to = layout.getPrimaryHorizontal(end)
                // Two different caret positions indicate a bidi boundary, not a simple edge.
                if (from != layout.getSecondaryHorizontal(start) || to != layout.getSecondaryHorizontal(end)) return null
                (to - from) * direction
            }
            if (!advance.isFinite() || advance < 0f) return null
            width += advance
        }
        if (end - start < text.length) return width
    }
    return null // A symbol-only track has no body to align.
}

private fun CharSequence.bodyStart(): Int = PronunciationAlignmentMath.bodyStart(this)

private fun CharSequence.bodyEnd(): Int = PronunciationAlignmentMath.bodyEnd(this)
