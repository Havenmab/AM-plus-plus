package dev.amenhancer.glass

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import dev.amenhancer.backdrop.TextureBlurKernel
import dev.amenhancer.backdrop.VibrantBlur
import dev.amenhancer.backdrop.textureBlur

/**
 * One place that decides how a glass surface blurs its backdrop.
 *
 * With [GlassPolicy.TEXTURE_BLUR_ENABLED] every surface takes the experimental texture path
 * (vibrancy + one runtime-shader pass) so the panels cannot drift apart visually; otherwise
 * the previous cached vibrancy+blur prefix is used unchanged.
 */
internal fun BackdropEffectScope.glassBackdropBlur(radiusPx: Float, material: VibrantBlur?) {
    if (GlassPolicy.TEXTURE_BLUR_ENABLED) {
        vibrancy()
        textureBlur(
            radiusPx,
            dither = GlassPolicy.TEXTURE_BLUR_DITHER,
            brightness = GlassPolicy.GLASS_BLUR_BRIGHTNESS,
        )
        return
    }
    if (material != null) {
        material.applyTo(this)
    } else {
        vibrancy()
        blur(radiusPx)
    }
    colorControls(brightness = GlassPolicy.GLASS_BLUR_BRIGHTNESS)
}

/**
 * Layer rasterisation factor for a surface blurring [radiusPx].
 *
 * A big visual radius needs a wide kernel; sampling that kernel on full-resolution pixels
 * leaves only a handful of samples per radius, which shows up as ghost copies of the backdrop.
 * Rasterising the layer at roughly one tap spacing first keeps the taps dense (≈1-2 layer
 * pixels apart) and the blur smooth, and it is also where most of the GPU saving comes from.
 */
internal fun glassDownscaleFactor(radiusPx: Float): Int =
    if (GlassPolicy.TEXTURE_BLUR_ENABLED) {
        TextureBlurKernel.stepFor(radiusPx).coerceIn(1, GlassPolicy.TEXTURE_BLUR_MAX_DOWNSCALE)
    } else {
        1
    }