package dev.amenhancer.module.hook

/**
 * Turns a stream of vertical scroll deltas into a collapse/expand intent.
 *
 * The bar only flips after the user has committed to a direction by scrolling a threshold's
 * worth, so a small jitter or a one-pixel bounce never moves the bar. Accumulating in the
 * direction of travel (rather than reacting per event) is what makes the gesture feel like an
 * intentional flick rather than a twitch, and reversing direction clears the accumulator so a
 * change of mind does not have to first pay back the previous travel.
 *
 * This deliberately accumulates **measured displacement** rather than the unscrolled remainder a
 * nested-scroll connection reports. At the end of a list the remainder is non-zero while nothing
 * moves, so a remainder-driven accumulator would collapse the bar on a flick that scrolled
 * nothing.
 *
 * Android-free so the flip rules stay unit-testable.
 */
internal class GlassScrollCollapseAccumulator(private val thresholdPx: Float) {
    private var accumulator = 0f

    /**
     * Returns `true` to collapse, `false` to expand, or `null` to leave the bar as it is.
     *
     * [deltaY] is the scroll displacement since the previous observation: negative when the
     * content moved up (the user is scrolling down the page).
     */
    fun accumulate(deltaY: Int): Boolean? {
        if (thresholdPx <= 0f || !thresholdPx.isFinite() || deltaY == 0) return null
        val delta = deltaY.toFloat()
        if (!delta.isFinite()) return null
        // A reversal discards the previous direction's progress before accumulating anew.
        if (delta < 0f && accumulator > 0f) accumulator = 0f
        if (delta > 0f && accumulator < 0f) accumulator = 0f
        accumulator += delta
        return when {
            accumulator <= -thresholdPx -> { accumulator = 0f; true }
            accumulator >= thresholdPx -> { accumulator = 0f; false }
            else -> null
        }
    }

    /** Drops partial travel, so a resumed scroll starts from rest instead of a stale count. */
    fun reset() { accumulator = 0f }
}
