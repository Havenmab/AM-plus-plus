package dev.amenhancer.backdrop

/**
 * Symmetric Gaussian taps for [textureBlur], expressed in **downsampled** pixels.
 *
 * The blur runs as one runtime-shader pass: the shader samples the captured content at
 * `xy ± offset * downscale` and weights each pair. Doing the low-pass on a coarser grid
 * (the same idea miuix-blur uses with its downsample shaders) means a handful of taps
 * covers a large visual radius, so the fragment shader never runs a long kernel.
 */
class TextureBlurTaps(
    /** Grid size the taps were computed for: 1 (full), 2 or 4. */
    val downscale: Int,
    /** Positive offsets in downsampled pixels; the shader samples ±offset. */
    val offsetsPx: FloatArray,
    /** Center weight followed by one weight per offset pair; sums to 1. */
    val weights: FloatArray,
) {
    val pairCount: Int get() = offsetsPx.size
}

/** Pure math behind [textureBlur]; kept platform-free so it stays unit-testable. */
object TextureBlurKernel {

    /** Radius-to-sigma mapping used by the rest of the glass pipeline. */
    const val RADIUS_TO_SIGMA = 0.45f

    /** Upper bound on symmetric tap pairs; the shader is generated per count. */
    const val MAX_PAIRS = 7

    /** Taps below this weight contribute less than one 8-bit level and are dropped. */
    const val WEIGHT_THRESHOLD = 0.002f

    /**
     * Grid size for [radiusPx]: small radii keep full resolution (their kernel is short
     * anyway), medium radii blur on a half grid, large ones on a quarter grid.
     */
    fun downscaleFor(radiusPx: Float): Int = when {
        !radiusPx.isFinite() || radiusPx <= 0f -> 1
        radiusPx >= 48f -> 4
        radiusPx >= 12f -> 2
        else -> 1
    }

    /**
     * Gaussian taps for [radiusPx] on a [downscale] grid.
     *
     * The sigma is scaled into the coarse grid, the kernel is cut where a tap weight falls
     * below [WEIGHT_THRESHOLD], and the pair count is capped at [MAX_PAIRS].
     */
    fun taps(radiusPx: Float, downscale: Int = downscaleFor(radiusPx)): TextureBlurTaps {
        val grid = downscale.coerceIn(1, 4)
        val safeRadius = if (radiusPx.isFinite() && radiusPx > 0f) radiusPx else 0f
        val sigma = (safeRadius * RADIUS_TO_SIGMA / grid).coerceAtLeast(0.0001f)

        val pairWeights = ArrayList<Float>(MAX_PAIRS)
        var reach = 0
        for (i in 1..MAX_PAIRS) {
            val weight = kotlin.math.exp(-(i.toFloat() * i) / (2f * sigma * sigma))
            if (weight < WEIGHT_THRESHOLD) break
            pairWeights += weight
            reach = i
        }
        if (reach == 0) {
            return TextureBlurTaps(grid, FloatArray(0), floatArrayOf(1f))
        }

        val total = 1f + 2f * pairWeights.sum()
        val weights = FloatArray(reach + 1)
        weights[0] = 1f / total
        // A pair covers both sides of the centre, so its weight doubles in the normalisation.
        for (i in 0 until reach) weights[i + 1] = 2f * pairWeights[i] / total

        return TextureBlurTaps(
            downscale = grid,
            offsetsPx = FloatArray(reach) { index -> (index + 1).toFloat() },
            weights = weights,
        )
    }
}