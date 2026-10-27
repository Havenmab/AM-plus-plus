package dev.amenhancer.backdrop

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.internal.RuntimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.max

/**
 * AM++ texture blur: one runtime-shader pass whose taps live on a coarser grid.
 *
 * Compared with [blur] (Skia's full-resolution BlurEffect) this trades the same visual
 * radius for far fewer samples: the kernel is evaluated on a 1/2 or 1/4 grid and each tap
 * pair reaches `downscale` pixels of the original surface, which is the trick miuix-blur
 * uses with its downsample shaders. Shader sources are built per tap count and cached by
 * the scope's runtime-shader cache, so a steady frame only uploads uniforms.
 *
 * Falls back to [blur] whenever runtime shaders are unavailable (API < 33), so a device
 * without AGSL keeps the previous rendering path.
 */
fun BackdropEffectScope.textureBlur(
    radiusPx: Float,
    downscale: Int = TextureBlurKernel.downscaleFor(radiusPx),
    dither: Boolean = false,
) {
    if (!isRuntimeShaderSupported()) {
        blur(radiusPx)
        return
    }
    if (!radiusPx.isFinite() || radiusPx <= 0f) return

    val taps = TextureBlurKernel.taps(radiusPx, downscale)
    // The kernel samples up to pairCount * grid pixels out; keep those inside the layer so
    // the clamp never folds the edge back into the blur.
    val reach = max(radiusPx, taps.pairCount.toFloat() * taps.downscale)
    padding = max(padding, reach)

    // AGSL has a built-in `step()` function, so the grid uniform must not be called `step`.
    val shader = runCatching {
        obtainRuntimeShader(
            key = "AMPP_TextureBlur_" + taps.pairCount + "_" + taps.downscale + "_" + dither,
            string = shaderSource(taps.pairCount, taps.downscale, dither),
        )
    }.getOrNull()
    if (shader == null) {
        // A shader the driver rejects must never take the process down.
        blur(radiusPx)
        return
    }
    shader.setFloatUniform("grid", taps.downscale.toFloat())
    shader.setFloatUniform("size", size.width, size.height)
    if (taps.pairCount > 0) {
        shader.setFloatUniform("offsets", taps.offsetsPx)
    }
    shader.setFloatUniform("weights", taps.weights)
    val runtimeEffect = runCatching { RuntimeShaderEffect(shader, "content") }.getOrNull()
    if (runtimeEffect == null) {
        blur(radiusPx)
        return
    }
    effect(runtimeEffect)
}

private fun shaderSource(pairCount: Int, downscale: Int, dither: Boolean): String {
    val pairs = if (pairCount == 0) {
        ""
    } else {
        buildString {
            append("    for (int i = 0; i < ").append(pairCount).append("; i++) {\n")
            append("        float2 d = float2(offsets[i] * grid, 0.0);\n")
            append("        color += (content.eval(clamp(c + d, lo, hi)) + content.eval(clamp(c - d, lo, hi))) * half(weights[i + 1]);\n")
            append("    }\n")
        }
    }
    val ditherLine = if (!dither) {
        ""
    } else {
        "    float n = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453) - 0.5;\n" +
            "    color.rgb += half3(n * 0.0039);\n"
    }
    val header = "uniform shader content;\n" +
        "uniform float2 size;\n" +
        "uniform float grid;\n" +
        // AGSL rejects a zero-length uniform array, so the center-only kernel omits it.
        (if (pairCount > 0) "uniform float offsets[" + pairCount + "];\n" else "") +
        "uniform float weights[" + (pairCount + 1) + "];\n"
    val body = "\nhalf4 main(float2 xy) {\n" +
        "    float2 lo = float2(0.5);\n" +
        "    float2 hi = size - float2(0.5);\n" +
        "    float2 c = clamp(xy, lo, hi);\n" +
        "    half4 color = content.eval(c) * half(weights[0]);\n" +
        pairs + ditherLine +
        "    return color;\n" +
        "}\n"
    return header + body
}