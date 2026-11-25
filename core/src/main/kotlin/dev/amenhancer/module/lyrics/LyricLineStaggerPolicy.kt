package dev.amenhancer.module.lyrics

/**
 * AMLL's per-row stagger: a line change is a wave, not one rigid block.
 *
 * AMLL moves every line with its own spring and gives each one a start delay
 * (`base/index.ts` 960-1006):
 *
 * ```
 * let delay = 0, baseDelay = 0.05;                  // 50 ms per row
 * for (i in lines) {
 *   group.setTransform(curPos, snap, delay, ...)     // this row waits `delay`, then springs
 *   if (curPos + lineH >= 0) {                       // only rows still on (or near) the screen
 *     delay += baseDelay;
 *     if (i >= scrollToIndex) baseDelay *= 1 / 1.05; // past the focus line the wave tightens
 *   }
 * }
 * ```
 *
 * The effect is the one 乙-1 lacked: the top of the block leaves first and the rows below follow
 * 50 ms apart, so a line change reads as rows chasing each other instead of one rectangle sliding.
 * Past the focus line the spacing tightens by 5 % per row, which keeps a long visible window from
 * smearing the wave over half a second.
 *
 * The delay is a pure function of (rank, focus rank): no clock, no view, testable on the JVM.
 */
object LyricLineStaggerPolicy {
    /** AMLL's `baseDelay`: 50 ms between neighbouring rows. */
    const val BASE_DELAY_MS: Float = 50f

    /** AMLL's `baseDelay *= 1/1.05` past the focus line: 5 % tighter per row. */
    const val NARROWING: Float = 1.05f

    /** AMLL's `alignPosition`: the focus line sits at 35 % of the pane's height. */
    const val ALIGN_POSITION: Float = 0.35f

    /**
     * The delay of the row at [rank] (0 = the topmost row of the line change), given the rank of
     * the row sitting on the focus line.
     *
     * Exactly AMLL's loop: the delay assigned to a row is the sum of the spacings of the rows above
     * it, and the spacing narrows from the focus line down. A [focusRank] at or past the last row
     * means "no narrowing", which is also the fallback when the pane's height is unknown.
     */
    fun delayMs(rank: Int, focusRank: Int): Float {
        if (rank <= 0) return 0f
        var delay = 0f
        var base = BASE_DELAY_MS
        var index = 0
        while (index < rank) {
            delay += base
            if (index >= focusRank) base /= NARROWING
            index += 1
        }
        return delay
    }

    /**
     * The rank of the first row at or below the focus line, from the line change's layout tops.
     *
     * The tops are the rows' post-layout `toY` in the list's own coordinates, so this is the row
     * sitting on `ALIGN_POSITION * paneHeight` — AMLL's `scrollToIndex`, derived geometrically
     * because Apple Music's animator exposes no index. No row reaching the line (a short visible
     * window, an unknown height) yields "nothing past the focus", i.e. an even 50 ms stagger.
     */
    fun focusRank(layoutTops: List<Int>, paneHeightPx: Int): Int {
        if (layoutTops.isEmpty()) return 0
        if (paneHeightPx <= 0) return layoutTops.size
        val line = paneHeightPx * ALIGN_POSITION
        val index = layoutTops.indexOfFirst { it >= line }
        return if (index < 0) layoutTops.size else index
    }
}
