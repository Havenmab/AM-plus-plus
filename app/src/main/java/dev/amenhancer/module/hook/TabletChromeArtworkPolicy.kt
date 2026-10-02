package dev.amenhancer.module.hook

internal object TabletChromeArtworkPolicy {
    const val HANDOFF_END = 0.35f

    data class Transform(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
    )

    data class Layout(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val pivotX: Float,
        val pivotY: Float,
    )

    data class Frame(val left: Float, val top: Float, val width: Float, val height: Float)

    fun ownsMiniCover(progress: Float, aligned: Boolean, nativeVisible: Boolean): Boolean =
        progress.isFinite() && progress > 0f && aligned && nativeVisible

    fun align(native: Transform, layout: Layout, target: Frame?, progress: Float): Transform? {
        if (!progress.isFinite() || !native.valid() || !layout.valid()) return null
        val slide = progress.coerceIn(0f, 1f)
        if (slide >= HANDOFF_END) return native
        val frame = target?.takeIf { it.valid() } ?: return null
        val scaleX = frame.width / layout.width
        val scaleY = frame.height / layout.height
        val destination = Transform(
            scaleX,
            scaleY,
            frame.left - layout.left - layout.pivotX * (1f - scaleX),
            frame.top - layout.top - layout.pivotY * (1f - scaleY),
        )
        if (!destination.valid()) return null
        if (slide == 0f) return destination
        val fraction = slide / HANDOFF_END
        val weight = 1f - fraction * fraction * (3f - 2f * fraction)
        return Transform(
            native.scaleX + (destination.scaleX - native.scaleX) * weight,
            native.scaleY + (destination.scaleY - native.scaleY) * weight,
            native.translationX + (destination.translationX - native.translationX) * weight,
            native.translationY + (destination.translationY - native.translationY) * weight,
        ).takeIf { it.valid() }
    }

    private fun Transform.valid(): Boolean =
        scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f &&
            translationX.isFinite() && translationY.isFinite()

    private fun Layout.valid(): Boolean =
        left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() &&
            width > 0f && height > 0f && pivotX.isFinite() && pivotY.isFinite()

    private fun Frame.valid(): Boolean =
        left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() &&
            width > 0f && height > 0f
}
