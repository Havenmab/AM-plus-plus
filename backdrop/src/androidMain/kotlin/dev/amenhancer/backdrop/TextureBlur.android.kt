package dev.amenhancer.backdrop

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.internal.RuntimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.max

/**
 * AM++ texture blur: one runtime-shader pass with symmetric tap pairs.
 *
 * Compared with [blur] (Skia's full-resolution BlurEffect) this replaces a long per-pixel
 * kernel with [TextureBlurKernel.MAX_PAIRS] wide taps that still cover the whole 3σ reach,
 * which is the same trade miuix-blur makes with its downsample shaders. Shader sources are
 * built per tap count and cached by the scope's runtime-shader cache, so a steady frame only
 * uploads uniforms.
 *
 * Every failure path (no AGSL, driver rejecting the shader, effect creation) falls back to
 * [blur], so this can never take the host process down.
 */
fun BackdropEffectScope.textureBlur(
    radiusPx: Float,
    stepPx: Int = TextureBlurKernel.stepFor(radiusPx),
    dither: Boolean = false,
    brightness: Float = 0f,
) {
    if (!isRuntimeShaderSupported()) {
        blur(radiusPx)
        return
    }
    if (!radiusPx.isFinite() || radiusPx <= 0f) return

    // The effect runs in layer pixels, which may be downscaled by the draw path.
    val layerScale = if (rasterScale.isFinite() && rasterScale >= 1f) rasterScale else 1f
    val layerRadius = radiusPx / layerScale
    val taps = TextureBlurKernel.taps(layerRadius, stepPx)
    val reach = max(layerRadius, taps.offsetsPx.lastOrNull() ?: 0f)
    padding = max(padding, reach * layerScale)

    // AGSL has a built-in `step()` function, so no uniform may be named `step`.
    val shader = runCatching {
        obtainRuntimeShader(
            key = "AMPP_TextureBlur_" + taps.pairCount + "_" + taps.stepPx + "_" + dither,
            string = shaderSource(taps.pairCount, dither),
        )
    }.getOrNull()
    if (shader == null) {
        blur(radiusPx)
        return
    }
    runCatching {
        shader.setFloatUniform("size", size.width / layerScale, size.height / layerScale)
        shader.setFloatUniform("brightness", brightness)
        if (taps.pairCount > 0) shader.setFloatUniform("offsets", taps.offsetsPx)
        shader.setFloatUniform("weights", taps.weights)
    }
    val runtimeEffect = runCatching { RuntimeShaderEffect(shader, "content") }.getOrNull()
    if (runtimeEffect == null) {
        blur(radiusPx)
        return
    }
    effect(runtimeEffect)
}

private fun shaderSource(pairCount: Int, dither: Boolean): String {
    val pairs = if (pairCount == 0) {
        ""
    } else {
        buildString {
            append("    for (int i = 0; i < ").append(pairCount).append("; i++) {\n")
            append("        float2 d = float2(offsets[i], 0.0);\n")
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
        "uniform float brightness;\n" +
        // AGSL rejects a zero-length uniform array, so the center-only kernel omits it.
        (if (pairCount > 0) "uniform float offsets[" + pairCount + "];\n" else "") +
        "uniform float weights[" + (pairCount + 1) + "];\n"
    val body = "\nhalf4 main(float2 xy) {\n" +
        "    float2 lo = float2(0.5);\n" +
        "    float2 hi = size - float2(0.5);\n" +
        "    float2 c = clamp(xy, lo, hi);\n" +
        "    half4 color = content.eval(c) * half(weights[0]);\n" +
        pairs + ditherLine +
        "    color.rgb += half3(brightness * color.a);\n" +
        "    return color;\n" +
        "}\n"
    return header + body
}