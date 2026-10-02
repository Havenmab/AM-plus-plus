package dev.amenhancer.module.hook

import kotlin.math.roundToInt

internal object TabletChromeEdgeFadePolicy {
    const val HEIGHT_DP = 28

    fun heightPx(density: Float, inset: Int): Int =
        inset.coerceAtLeast(0) + if (density.isFinite() && density > 0f) (HEIGHT_DP * density).roundToInt() else 0

    fun opacity(enabled: Boolean, progress: Float): Float =
        if (enabled && progress.isFinite()) 1f - TabletChromeLayoutPolicy.expandHideFactor(progress) else 0f
}
