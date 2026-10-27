package dev.amenhancer.glass

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
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
        textureBlur(radiusPx, dither = GlassPolicy.TEXTURE_BLUR_DITHER)
        return
    }
    if (material != null) {
        material.applyTo(this)
    } else {
        vibrancy()
        blur(radiusPx)
    }
}

/** Layer rasterisation factor the draw path should use for a glass surface. */
internal fun glassDownscaleFactor(): Int =
    if (GlassPolicy.TEXTURE_BLUR_ENABLED) GlassPolicy.TEXTURE_BLUR_DOWNSCALE else 1