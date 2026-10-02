package dev.amenhancer.glass

internal data class TabThumbBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val radiusX: Float,
    val radiusY: Float,
)

internal object TabThumbGeometry {
    fun scaleX(pressedScale: Float, velocity: Float): Float =
        pressedScale / (1f - (velocity / 10f * 0.75f).coerceIn(-0.2f, 0.2f))

    fun scaleY(pressedScale: Float, velocity: Float): Float =
        pressedScale * (1f - (velocity / 10f * 0.25f).coerceIn(-0.2f, 0.2f))

    fun bounds(
        panelWidth: Float,
        tabWidth: Float,
        thumbHeight: Float,
        inset: Float,
        index: Float,
        isLtr: Boolean,
        scaleX: Float,
        scaleY: Float,
    ): TabThumbBounds? {
        if (!panelWidth.isFinite() || !tabWidth.isFinite() || !thumbHeight.isFinite() ||
            !inset.isFinite() || !index.isFinite() || !scaleX.isFinite() || !scaleY.isFinite() ||
            panelWidth <= 0f || tabWidth <= 0f || thumbHeight <= 0f || scaleX <= 0f || scaleY <= 0f
        ) return null
        val centre = if (isLtr) inset + (index + 0.5f) * tabWidth
            else panelWidth - inset - (index + 0.5f) * tabWidth
        val width = tabWidth * scaleX
        val height = thumbHeight * scaleY
        val radius = minOf(tabWidth, thumbHeight) / 2f
        return TabThumbBounds(
            left = centre - width / 2f,
            top = (thumbHeight - height) / 2f,
            width = width,
            height = height,
            radiusX = radius * scaleX,
            radiusY = radius * scaleY,
        )
    }
}
