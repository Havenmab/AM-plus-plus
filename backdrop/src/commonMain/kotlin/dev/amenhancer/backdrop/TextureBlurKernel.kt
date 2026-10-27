package dev.amenhancer.backdrop

/**
 * Symmetric Gaussian taps for [textureBlur], in **full-resolution** pixels.
 *
 * One runtime-shader pass samples the captured content at `xy ± offset` for every tap and
 * weights the pair. Offsets are spaced [TextureBlurTaps.stepPx] apart so the same short
 * kernel covers the whole 3σ reach of a large radius (the trick miuix-blur gets from its
 * downsample shaders: fewer, wider samples instead of a long 1-pixel kernel).
 */
class TextureBlurTaps(
    /** Distance between neighbouring taps, in pixels; 1 is a per-pixel kernel. */
    val stepPx: Int,
    /** Positive offsets in pixels; the shader samples ±offset. */
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

    /** How many sigmas the kernel must reach to look like a full blur. */
    const val SIGMA_REACH = 3f

    /** Taps below this weight contribute less than one 8-bit level and are dropped. */
    const val WEIGHT_THRESHOLD = 0.002f

    /**
     * Tap spacing for [radiusPx]: wide enough that [MAX_PAIRS] pairs still cover the 3σ
     * reach, and never below one pixel (small radii stay exact).
     */
    fun stepFor(radiusPx: Float): Int {
        if (!radiusPx.isFinite() || radiusPx <= 0f) return 1
        val sigma = radiusPx * RADIUS_TO_SIGMA
        val reach = sigma * SIGMA_REACH
        val step = kotlin.math.ceil(reach / MAX_PAIRS).toInt()
        return step.coerceIn(1, 64)
    }

    /** Gaussian taps for [radiusPx], spaced [stepPx] pixels apart. */
    fun taps(radiusPx: Float, stepPx: Int = stepFor(radiusPx)): TextureBlurTaps {
        val step = stepPx.coerceIn(1, 64)
        val safeRadius = if (radiusPx.isFinite() && radiusPx > 0f) radiusPx else 0f
        val sigma = (safeRadius * RADIUS_TO_SIGMA).coerceAtLeast(0.0001f)

        val pairWeights = ArrayList<Float>(MAX_PAIRS)
        var reach = 0
        for (i in 1..MAX_PAIRS) {
            val offset = (i * step).toFloat()
            val weight = kotlin.math.exp(-(offset * offset) / (2f * sigma * sigma))
            if (weight < WEIGHT_THRESHOLD) break
            pairWeights += weight
            reach = i
        }
        if (reach == 0) {
            return TextureBlurTaps(step, FloatArray(0), floatArrayOf(1f))
        }

        val total = 1f + 2f * pairWeights.sum()
        val weights = FloatArray(reach + 1)
        weights[0] = 1f / total
        // A pair covers both sides of the centre, so its weight doubles in the normalisation.
        for (i in 0 until reach) weights[i + 1] = 2f * pairWeights[i] / total

        return TextureBlurTaps(
            stepPx = step,
            offsetsPx = FloatArray(reach) { index -> ((index + 1) * step).toFloat() },
            weights = weights,
        )
    }
}