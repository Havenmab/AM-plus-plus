package dev.amenhancer.module.hook

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class TabletChromeEdgeFades : AutoCloseable {
    private val drawable = FadeDrawable()
    private var source: View? = null
    private val sourceLocation = IntArray(2)
    private val windowLocation = IntArray(2)

    fun update(content: View?, window: View, topHeight: Int, bottomHeight: Int, color: Int, opacity: Float) {
        if (content == null || opacity <= 0f || content.width <= 0 || content.height <= 0) {
            close()
            return
        }
        if (source !== content) {
            close()
            source = content
            content.overlay.add(drawable)
        }
        content.getLocationOnScreen(sourceLocation)
        window.getLocationOnScreen(windowLocation)
        val top = windowLocation[1] - sourceLocation[1]
        drawable.configure(
            Geometry(content.width, content.height, top, top + topHeight, top + window.height - bottomHeight, top + window.height, color),
        )
        drawable.alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
    }

    override fun close() {
        source?.overlay?.remove(drawable)
        source = null
    }

    private data class Geometry(
        val width: Int, val height: Int, val topStart: Int, val topEnd: Int,
        val bottomStart: Int, val bottomEnd: Int, val color: Int,
    )

    private class FadeDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var geometry: Geometry? = null
        private var topShader: Shader? = null
        private var bottomShader: Shader? = null

        fun configure(next: Geometry) {
            if (next == geometry) return
            geometry = next
            setBounds(0, 0, next.width, next.height)
            val opaque = next.color or 0xff000000.toInt()
            val transparent = next.color and 0x00ffffff
            topShader = gradient(next.topStart, next.topEnd, opaque, transparent)
            bottomShader = gradient(next.bottomStart, next.bottomEnd, transparent, opaque)
            invalidateSelf()
        }

        private fun gradient(start: Int, end: Int, from: Int, to: Int): Shader? = if (end > start) {
            LinearGradient(0f, start.toFloat(), 0f, end.toFloat(), from, to, Shader.TileMode.CLAMP)
        } else null

        override fun draw(canvas: Canvas) {
            val frame = geometry ?: return
            drawBand(canvas, frame, frame.topStart, frame.topEnd, topShader)
            drawBand(canvas, frame, frame.bottomStart, frame.bottomEnd, bottomShader)
        }

        private fun drawBand(canvas: Canvas, frame: Geometry, start: Int, end: Int, shader: Shader?) {
            val top = max(0, start)
            val bottom = min(frame.height, end)
            if (top >= bottom || shader == null) return
            paint.shader = shader
            canvas.drawRect(0f, top.toFloat(), frame.width.toFloat(), bottom.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {
            if (paint.alpha == alpha) return
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun getAlpha(): Int = paint.alpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
