package dev.amenhancer.module.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.ImageView

internal enum class EmbeddedSettingsPage {
    MAIN,
    CUSTOM_LYRICS,
}

internal data class EmbeddedLyricsEditorAction(
    val label: String,
    val compactLabel: String = label,
    val onClick: () -> Unit,
)


/**
 * Code-owned rendition of the actual AM++ application icon (`ic_module.xml`).
 *
 * It intentionally avoids loading the module drawable through Apple Music's
 * package Context, which is subject to package-visibility failures in the
 * injected process. The paths and gradients below mirror the source icon.
 */
internal class EmbeddedAmppBrandDrawable : android.graphics.drawable.Drawable() {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaValue = 255
    private var colorFilterValue: ColorFilter? = null

    private val aPath = Path().apply {
        moveTo(407f, 326f)
        cubicTo(386f, 339f, 376f, 357f, 366f, 380f)
        lineTo(161f, 844f)
        cubicTo(149f, 871f, 158f, 899f, 181f, 915f)
        cubicTo(207f, 934f, 240f, 922f, 254f, 892f)
        lineTo(424f, 492f)
        cubicTo(428f, 481f, 432f, 473f, 439f, 471f)
        cubicTo(447f, 469f, 451f, 476f, 457f, 487f)
        lineTo(672f, 903f)
        cubicTo(684f, 926f, 711f, 932f, 735f, 920f)
        cubicTo(759f, 908f, 771f, 883f, 759f, 856f)
        lineTo(504f, 359f)
        cubicTo(494f, 338f, 475f, 322f, 454f, 317f)
        cubicTo(438f, 313f, 421f, 317f, 407f, 326f)
        close()
    }
    private val notePath = Path().apply {
        moveTo(509f, 638f)
        lineTo(431f, 646f)
        cubicTo(422f, 647f, 417f, 654f, 417f, 664f)
        lineTo(420f, 801f)
        cubicTo(406f, 794f, 391f, 790f, 374f, 790f)
        cubicTo(334f, 790f, 303f, 819f, 303f, 857f)
        cubicTo(303f, 898f, 334f, 929f, 373f, 929f)
        cubicTo(417f, 929f, 451f, 899f, 451f, 857f)
        lineTo(451f, 739f)
        cubicTo(451f, 726f, 457f, 719f, 469f, 717f)
        lineTo(512f, 712f)
        cubicTo(526f, 710f, 534f, 700f, 532f, 687f)
        lineTo(526f, 651f)
        cubicTo(524f, 642f, 519f, 638f, 509f, 638f)
        close()
    }
    private val mPath = Path().apply {
        moveTo(635f, 463f)
        cubicTo(618f, 478f, 610f, 500f, 614f, 522f)
        cubicTo(616f, 534f, 620f, 543f, 626f, 555f)
        lineTo(724f, 740f)
        lineTo(724f, 670f)
        cubicTo(724f, 660f, 728f, 651f, 734f, 650f)
        cubicTo(741f, 650f, 746f, 657f, 752f, 666f)
        lineTo(803f, 750f)
        cubicTo(813f, 764f, 826f, 772f, 840f, 769f)
        cubicTo(850f, 768f, 858f, 759f, 866f, 749f)
        lineTo(927f, 668f)
        cubicTo(934f, 659f, 938f, 656f, 942f, 661f)
        cubicTo(944f, 665f, 943f, 673f, 943f, 679f)
        lineTo(943f, 885f)
        cubicTo(943f, 911f, 965f, 930f, 991f, 930f)
        cubicTo(1020f, 930f, 1043f, 908f, 1043f, 880f)
        lineTo(1043f, 525f)
        cubicTo(1043f, 495f, 1020f, 473f, 990f, 473f)
        cubicTo(972f, 473f, 958f, 480f, 946f, 494f)
        lineTo(833f, 636f)
        lineTo(713f, 472f)
        cubicTo(694f, 447f, 658f, 445f, 635f, 463f)
        close()
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        val left = box.left + (box.width() - size) / 2f
        val top = box.top + (box.height() - size) / 2f
        val scale = size / VIEWPORT
        configurePaints()

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawRoundRect(RectF(0f, 0f, VIEWPORT, VIEWPORT), 282f, 282f, backgroundPaint)
        canvas.drawPath(aPath, primaryPaint)
        canvas.drawPath(notePath, primaryPaint)
        canvas.drawPath(mPath, accentPaint)
        canvas.drawRoundRect(RectF(794f, 307f, 823f, 440f), 14.5f, 14.5f, primaryPaint)
        canvas.drawRoundRect(RectF(746f, 358f, 879f, 388f), 15f, 15f, primaryPaint)
        canvas.drawRoundRect(RectF(963f, 307f, 992f, 440f), 14.5f, 14.5f, primaryPaint)
        canvas.drawRoundRect(RectF(915f, 358f, 1047f, 388f), 15f, 15f, primaryPaint)
        canvas.restore()
    }

    private fun configurePaints() {
        backgroundPaint.shader = RadialGradient(
            627f, 564f, 941f,
            intArrayOf(Color.parseColor("#FDEEEE"), Color.parseColor("#FCEBEC")),
            null,
            Shader.TileMode.CLAMP,
        )
        primaryPaint.shader = LinearGradient(
            0f, 0f, VIEWPORT, VIEWPORT,
            Color.parseColor("#F45F6B"),
            Color.parseColor("#F66A72"),
            Shader.TileMode.CLAMP,
        )
        accentPaint.shader = LinearGradient(
            0f, 0f, VIEWPORT, VIEWPORT,
            Color.parseColor("#B05B91"),
            Color.parseColor("#AC5A8E"),
            Shader.TileMode.CLAMP,
        )
        listOf(backgroundPaint, primaryPaint, accentPaint).forEach { paint ->
            paint.alpha = alphaValue
            paint.colorFilter = colorFilterValue
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        colorFilterValue = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val VIEWPORT = 1254f
    }
}

/** Compact red music mark used by the current-song row in the reference UI. */
internal class EmbeddedMusicStatusDrawable : android.graphics.drawable.Drawable(), EmbeddedPaletteDrawable {
    private var colors = EmbeddedSettingsPalette.Light

    override fun applyPalette(colors: EmbeddedSettingsColors) {
        this.colors = colors
        invalidateSelf()
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val noteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        val outer = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
        val radius = size * 0.18f
        backgroundPaint.color = colors.primary
        backgroundPaint.alpha = alphaValue
        canvas.drawRoundRect(outer, radius, radius, backgroundPaint)
        noteStroke.color = colors.onPrimary
        noteStroke.alpha = alphaValue
        noteStroke.strokeWidth = size * 0.085f
        noteFill.color = colors.onPrimary
        noteFill.alpha = alphaValue
        val stemX = outer.left + size * 0.59f
        canvas.drawLine(stemX, outer.top + size * 0.22f, stemX, outer.top + size * 0.67f, noteStroke)
        canvas.drawLine(stemX, outer.top + size * 0.22f, outer.left + size * 0.76f, outer.top + size * 0.17f, noteStroke)
        canvas.drawCircle(outer.left + size * 0.41f, outer.top + size * 0.7f, size * 0.14f, noteFill)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        noteStroke.colorFilter = colorFilter
        noteFill.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal enum class EmbeddedGlyphKind {
    Music,
    Exchange,
    Github,
    CloudBackup,
    DocumentSearch,
    TabletDualPane,
    BottomBar,
    VideoDisplay,
    Glass,
    LyricsBlur,
    Document,
    Translate,
    Refresh,
    AddCircle,
    TtmlDocument,
    Search,
    Edit,
    Delete,
    BackArrow,
    ChevronRight,
    MoreVertical,
}

/** Small host-independent glyphs for the reference actions and empty state. */
internal class EmbeddedGlyphDrawable(
    private val kind: EmbeddedGlyphKind,
    private val tint: Int,
    private val strokeWidthFraction: Float = 0.08f,
) : android.graphics.drawable.Drawable(), EmbeddedPaletteDrawable {
    private var colors = EmbeddedSettingsPalette.Light

    override fun applyPalette(colors: EmbeddedSettingsColors) {
        this.colors = colors
        invalidateSelf()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        // Keep glyph geometry centered when an ImageView uses a rectangular
        // touch target (the overflow icon is 32/40×44dp).
        val left = box.left + (box.width() - size) / 2f
        val top = box.top + (box.height() - size) / 2f
        val cx = left + size / 2f
        val cy = top + size / 2f
        fill.color = colors.iconColor(tint)
        fill.alpha = alphaValue
        stroke.color = colors.iconColor(tint)
        stroke.alpha = alphaValue
        stroke.strokeWidth = size * strokeWidthFraction
        when (kind) {
            EmbeddedGlyphKind.Music -> {
                canvas.drawLine(cx + size * 0.1f, top + size * 0.2f, cx + size * 0.1f, top + size * 0.68f, stroke)
                canvas.drawLine(cx + size * 0.1f, top + size * 0.2f, cx + size * 0.34f, top + size * 0.15f, stroke)
                canvas.drawCircle(cx - size * 0.03f, top + size * 0.73f, size * 0.15f, fill)
            }
            EmbeddedGlyphKind.Exchange -> {
                val y1 = cy - size * 0.15f
                val y2 = cy + size * 0.15f
                canvas.drawLine(left + size * 0.22f, y1, left + size * 0.72f, y1, stroke)
                canvas.drawLine(left + size * 0.28f, y2, left + size * 0.78f, y2, stroke)
                canvas.drawLine(left + size * 0.72f, y1, left + size * 0.56f, y1 - size * 0.14f, stroke)
                canvas.drawLine(left + size * 0.72f, y1, left + size * 0.56f, y1 + size * 0.14f, stroke)
                canvas.drawLine(left + size * 0.28f, y2, left + size * 0.44f, y2 - size * 0.14f, stroke)
                canvas.drawLine(left + size * 0.28f, y2, left + size * 0.44f, y2 + size * 0.14f, stroke)
            }
            EmbeddedGlyphKind.Github -> {
                // Match the reference's Octocat silhouette rather than the
                // previous generic cat face. The even-odd face opening keeps
                // the mark legible on the pale action surface at small sizes.
                val octocat = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    moveTo(cx, top + size * 0.16f)
                    cubicTo(
                        cx - size * 0.13f, top + size * 0.16f,
                        cx - size * 0.24f, top + size * 0.19f,
                        cx - size * 0.31f, top + size * 0.26f,
                    )
                    lineTo(cx - size * 0.4f, top + size * 0.17f)
                    lineTo(cx - size * 0.35f, top + size * 0.39f)
                    cubicTo(
                        cx - size * 0.4f, top + size * 0.48f,
                        cx - size * 0.38f, top + size * 0.61f,
                        cx - size * 0.3f, top + size * 0.7f,
                    )
                    cubicTo(
                        cx - size * 0.24f, top + size * 0.78f,
                        cx - size * 0.15f, top + size * 0.82f,
                        cx - size * 0.07f, top + size * 0.83f,
                    )
                    lineTo(cx - size * 0.07f, top + size * 0.68f)
                    cubicTo(
                        cx - size * 0.13f, top + size * 0.69f,
                        cx - size * 0.16f, top + size * 0.66f,
                        cx - size * 0.16f, top + size * 0.61f,
                    )
                    lineTo(cx - size * 0.12f, top + size * 0.59f)
                    cubicTo(
                        cx - size * 0.1f, top + size * 0.64f,
                        cx - size * 0.06f, top + size * 0.67f,
                        cx - size * 0.02f, top + size * 0.67f,
                    )
                    lineTo(cx - size * 0.02f, top + size * 0.85f)
                    lineTo(cx + size * 0.02f, top + size * 0.85f)
                    lineTo(cx + size * 0.02f, top + size * 0.67f)
                    cubicTo(
                        cx + size * 0.06f, top + size * 0.67f,
                        cx + size * 0.1f, top + size * 0.64f,
                        cx + size * 0.12f, top + size * 0.59f,
                    )
                    lineTo(cx + size * 0.16f, top + size * 0.61f)
                    cubicTo(
                        cx + size * 0.16f, top + size * 0.66f,
                        cx + size * 0.13f, top + size * 0.69f,
                        cx + size * 0.07f, top + size * 0.68f,
                    )
                    lineTo(cx + size * 0.07f, top + size * 0.83f)
                    cubicTo(
                        cx + size * 0.15f, top + size * 0.82f,
                        cx + size * 0.24f, top + size * 0.78f,
                        cx + size * 0.3f, top + size * 0.7f,
                    )
                    cubicTo(
                        cx + size * 0.38f, top + size * 0.61f,
                        cx + size * 0.4f, top + size * 0.48f,
                        cx + size * 0.35f, top + size * 0.39f,
                    )
                    lineTo(cx + size * 0.4f, top + size * 0.17f)
                    lineTo(cx + size * 0.31f, top + size * 0.26f)
                    cubicTo(
                        cx + size * 0.24f, top + size * 0.19f,
                        cx + size * 0.13f, top + size * 0.16f,
                        cx, top + size * 0.16f,
                    )
                    close()
                    moveTo(cx - size * 0.2f, top + size * 0.34f)
                    cubicTo(
                        cx - size * 0.12f, top + size * 0.29f,
                        cx + size * 0.12f, top + size * 0.29f,
                        cx + size * 0.2f, top + size * 0.34f,
                    )
                    cubicTo(
                        cx + size * 0.24f, top + size * 0.42f,
                        cx + size * 0.22f, top + size * 0.51f,
                        cx + size * 0.17f, top + size * 0.58f,
                    )
                    cubicTo(
                        cx + size * 0.12f, top + size * 0.64f,
                        cx + size * 0.06f, top + size * 0.66f,
                        cx, top + size * 0.66f,
                    )
                    cubicTo(
                        cx - size * 0.06f, top + size * 0.66f,
                        cx - size * 0.12f, top + size * 0.64f,
                        cx - size * 0.17f, top + size * 0.58f,
                    )
                    cubicTo(
                        cx - size * 0.22f, top + size * 0.51f,
                        cx - size * 0.24f, top + size * 0.42f,
                        cx - size * 0.2f, top + size * 0.34f,
                    )
                    close()
                }
                canvas.drawPath(octocat, fill)
            }
            EmbeddedGlyphKind.CloudBackup -> {
                val cloud = Path().apply {
                    moveTo(left + size * 0.2f, top + size * 0.72f)
                    cubicTo(
                        left + size * 0.13f,
                        top + size * 0.69f,
                        left + size * 0.12f,
                        top + size * 0.57f,
                        left + size * 0.2f,
                        top + size * 0.5f,
                    )
                    cubicTo(
                        left + size * 0.24f,
                        top + size * 0.34f,
                        left + size * 0.4f,
                        top + size * 0.27f,
                        left + size * 0.53f,
                        top + size * 0.31f,
                    )
                    cubicTo(
                        left + size * 0.64f,
                        top + size * 0.34f,
                        left + size * 0.71f,
                        top + size * 0.42f,
                        left + size * 0.73f,
                        top + size * 0.51f,
                    )
                    cubicTo(
                        left + size * 0.84f,
                        top + size * 0.51f,
                        left + size * 0.89f,
                        top + size * 0.58f,
                        left + size * 0.89f,
                        top + size * 0.65f,
                    )
                    cubicTo(
                        left + size * 0.89f,
                        top + size * 0.75f,
                        left + size * 0.81f,
                        top + size * 0.8f,
                        left + size * 0.7f,
                        top + size * 0.8f,
                    )
                    lineTo(left + size * 0.24f, top + size * 0.8f)
                }
                canvas.drawPath(cloud, stroke)
                canvas.drawLine(cx - size * 0.12f, top + size * 0.62f, cx + size * 0.12f, top + size * 0.62f, stroke)
                canvas.drawLine(cx, top + size * 0.5f, cx, top + size * 0.74f, stroke)
            }
            EmbeddedGlyphKind.DocumentSearch -> {
                val document = RectF(
                    left + size * 0.22f,
                    top + size * 0.14f,
                    left + size * 0.64f,
                    top + size * 0.78f,
                )
                canvas.drawRoundRect(document, size * 0.04f, size * 0.04f, stroke)
                canvas.drawLine(left + size * 0.48f, top + size * 0.14f, left + size * 0.64f, top + size * 0.3f, stroke)
                canvas.drawCircle(left + size * 0.67f, top + size * 0.67f, size * 0.17f, stroke)
                canvas.drawLine(left + size * 0.79f, top + size * 0.79f, left + size * 0.9f, top + size * 0.9f, stroke)
            }
            EmbeddedGlyphKind.TabletDualPane -> {
                val device = RectF(
                    left + size * 0.18f,
                    top + size * 0.2f,
                    left + size * 0.82f,
                    top + size * 0.8f,
                )
                canvas.drawRoundRect(device, size * 0.06f, size * 0.06f, stroke)
                canvas.drawLine(left + size * 0.61f, device.top, left + size * 0.61f, device.bottom, stroke)
                canvas.drawCircle(left + size * 0.71f, top + size * 0.36f, size * 0.045f, fill)
                canvas.drawLine(left + size * 0.71f, top + size * 0.47f, left + size * 0.71f, top + size * 0.64f, stroke)
            }
            EmbeddedGlyphKind.BottomBar -> {
                val bulletX = left + size * 0.25f
                val lineStart = left + size * 0.4f
                val lineEnd = left + size * 0.78f
                listOf(0.3f, 0.5f, 0.7f).forEach { fraction ->
                    val y = top + size * fraction
                    canvas.drawCircle(bulletX, y, size * 0.06f, fill)
                    canvas.drawLine(lineStart, y, lineEnd, y, stroke)
                }
            }
            EmbeddedGlyphKind.VideoDisplay -> {
                val display = RectF(
                    left + size * 0.16f,
                    top + size * 0.22f,
                    left + size * 0.84f,
                    top + size * 0.68f,
                )
                canvas.drawRoundRect(display, size * 0.05f, size * 0.05f, stroke)
                canvas.drawLine(left + size * 0.5f, display.bottom, left + size * 0.5f, top + size * 0.8f, stroke)
                canvas.drawLine(left + size * 0.32f, top + size * 0.8f, left + size * 0.68f, top + size * 0.8f, stroke)
                val play = Path().apply {
                    moveTo(left + size * 0.45f, top + size * 0.33f)
                    lineTo(left + size * 0.45f, top + size * 0.57f)
                    lineTo(left + size * 0.66f, top + size * 0.45f)
                    close()
                }
                canvas.drawPath(play, fill)
            }
            EmbeddedGlyphKind.Glass -> {
                val bowl = Path().apply {
                    moveTo(left + size * 0.26f, top + size * 0.2f)
                    lineTo(left + size * 0.74f, top + size * 0.2f)
                    lineTo(left + size * 0.66f, top + size * 0.51f)
                    cubicTo(
                        left + size * 0.62f,
                        top + size * 0.63f,
                        left + size * 0.38f,
                        top + size * 0.63f,
                        left + size * 0.34f,
                        top + size * 0.51f,
                    )
                    close()
                }
                canvas.drawPath(bowl, stroke)
                canvas.drawLine(left + size * 0.27f, top + size * 0.36f, left + size * 0.73f, top + size * 0.36f, stroke)
                canvas.drawLine(cx, top + size * 0.62f, cx, top + size * 0.8f, stroke)
                canvas.drawLine(left + size * 0.34f, top + size * 0.8f, left + size * 0.66f, top + size * 0.8f, stroke)
            }
            EmbeddedGlyphKind.LyricsBlur -> {
                val document = RectF(
                    left + size * 0.2f,
                    top + size * 0.16f,
                    left + size * 0.63f,
                    top + size * 0.77f,
                )
                canvas.drawRoundRect(document, size * 0.04f, size * 0.04f, stroke)
                canvas.drawLine(left + size * 0.48f, top + size * 0.16f, left + size * 0.63f, top + size * 0.31f, stroke)
                canvas.drawLine(left + size * 0.3f, top + size * 0.39f, left + size * 0.53f, top + size * 0.39f, stroke)
                canvas.drawLine(left + size * 0.3f, top + size * 0.51f, left + size * 0.48f, top + size * 0.51f, stroke)
                canvas.drawCircle(left + size * 0.68f, top + size * 0.66f, size * 0.16f, stroke)
                canvas.drawLine(left + size * 0.8f, top + size * 0.78f, left + size * 0.89f, top + size * 0.87f, stroke)
            }
            EmbeddedGlyphKind.Document -> {
                val document = RectF(
                    left + size * 0.23f,
                    top + size * 0.14f,
                    left + size * 0.7f,
                    top + size * 0.82f,
                )
                canvas.drawRoundRect(document, size * 0.05f, size * 0.05f, stroke)
                canvas.drawLine(left + size * 0.51f, top + size * 0.14f, left + size * 0.7f, top + size * 0.33f, stroke)
                canvas.drawLine(left + size * 0.34f, top + size * 0.48f, left + size * 0.6f, top + size * 0.48f, stroke)
                canvas.drawLine(left + size * 0.34f, top + size * 0.62f, left + size * 0.6f, top + size * 0.62f, stroke)
            }
            EmbeddedGlyphKind.Translate -> {
                canvas.drawLine(left + size * 0.2f, top + size * 0.28f, left + size * 0.58f, top + size * 0.28f, stroke)
                canvas.drawLine(left + size * 0.39f, top + size * 0.16f, left + size * 0.39f, top + size * 0.31f, stroke)
                canvas.drawLine(left + size * 0.24f, top + size * 0.45f, left + size * 0.54f, top + size * 0.72f, stroke)
                canvas.drawLine(left + size * 0.54f, top + size * 0.45f, left + size * 0.24f, top + size * 0.72f, stroke)
                canvas.drawLine(left + size * 0.67f, top + size * 0.78f, left + size * 0.79f, top + size * 0.38f, stroke)
                canvas.drawLine(left + size * 0.91f, top + size * 0.78f, left + size * 0.79f, top + size * 0.38f, stroke)
                canvas.drawLine(left + size * 0.72f, top + size * 0.62f, left + size * 0.86f, top + size * 0.62f, stroke)
            }
            EmbeddedGlyphKind.Refresh -> {
                val oval = RectF(
                    left + size * 0.2f,
                    top + size * 0.2f,
                    left + size * 0.8f,
                    top + size * 0.8f,
                )
                canvas.drawArc(oval, 35f, 205f, false, stroke)
                canvas.drawArc(oval, 215f, 205f, false, stroke)
                val upperArrow = Path().apply {
                    moveTo(left + size * 0.68f, top + size * 0.17f)
                    lineTo(left + size * 0.82f, top + size * 0.2f)
                    lineTo(left + size * 0.75f, top + size * 0.32f)
                    close()
                }
                val lowerArrow = Path().apply {
                    moveTo(left + size * 0.32f, top + size * 0.83f)
                    lineTo(left + size * 0.18f, top + size * 0.8f)
                    lineTo(left + size * 0.25f, top + size * 0.68f)
                    close()
                }
                canvas.drawPath(upperArrow, fill)
                canvas.drawPath(lowerArrow, fill)
            }
            EmbeddedGlyphKind.AddCircle -> {
                val actionCenterY = top + size * 0.555f
                canvas.drawCircle(cx, actionCenterY, size * 0.3f, stroke)
                canvas.drawLine(
                    cx - size * 0.15f,
                    actionCenterY,
                    cx + size * 0.15f,
                    actionCenterY,
                    stroke,
                )
                canvas.drawLine(
                    cx,
                    actionCenterY - size * 0.15f,
                    cx,
                    actionCenterY + size * 0.15f,
                    stroke,
                )
            }
            EmbeddedGlyphKind.TtmlDocument -> {
                val document = RectF(
                    left + size * 0.22f,
                    top + size * 0.24f,
                    left + size * 0.74f,
                    top + size * 0.9f,
                )
                canvas.drawRoundRect(document, size * 0.045f, size * 0.045f, stroke)
                canvas.drawLine(left + size * 0.53f, top + size * 0.24f, left + size * 0.74f, top + size * 0.45f, stroke)
                canvas.drawLine(left + size * 0.38f, top + size * 0.55f, left + size * 0.29f, top + size * 0.64f, stroke)
                canvas.drawLine(left + size * 0.38f, top + size * 0.73f, left + size * 0.29f, top + size * 0.64f, stroke)
                canvas.drawLine(left + size * 0.55f, top + size * 0.53f, left + size * 0.47f, top + size * 0.75f, stroke)
                canvas.drawLine(left + size * 0.64f, top + size * 0.55f, left + size * 0.72f, top + size * 0.64f, stroke)
                canvas.drawLine(left + size * 0.64f, top + size * 0.73f, left + size * 0.72f, top + size * 0.64f, stroke)
            }
            EmbeddedGlyphKind.Search -> {
                canvas.drawCircle(left + size * 0.44f, top + size * 0.44f, size * 0.22f, stroke)
                canvas.drawLine(left + size * 0.6f, top + size * 0.6f, left + size * 0.82f, top + size * 0.82f, stroke)
            }
            EmbeddedGlyphKind.Edit -> {
                val pencil = Path().apply {
                    moveTo(left + size * 0.25f, top + size * 0.69f)
                    lineTo(left + size * 0.25f, top + size * 0.53f)
                    lineTo(left + size * 0.67f, top + size * 0.21f)
                    lineTo(left + size * 0.79f, top + size * 0.33f)
                    lineTo(left + size * 0.47f, top + size * 0.75f)
                    close()
                }
                canvas.drawPath(pencil, stroke)
                canvas.drawLine(left + size * 0.61f, top + size * 0.27f, left + size * 0.73f, top + size * 0.39f, stroke)
            }
            EmbeddedGlyphKind.Delete -> {
                val trash = RectF(
                    left + size * 0.3f,
                    top + size * 0.32f,
                    left + size * 0.7f,
                    top + size * 0.78f,
                )
                canvas.drawRoundRect(trash, size * 0.03f, size * 0.03f, stroke)
                canvas.drawLine(left + size * 0.24f, top + size * 0.25f, left + size * 0.76f, top + size * 0.25f, stroke)
                canvas.drawLine(left + size * 0.43f, top + size * 0.18f, left + size * 0.57f, top + size * 0.18f, stroke)
                canvas.drawLine(left + size * 0.43f, top + size * 0.42f, left + size * 0.43f, top + size * 0.68f, stroke)
                canvas.drawLine(left + size * 0.57f, top + size * 0.42f, left + size * 0.57f, top + size * 0.68f, stroke)
            }
            EmbeddedGlyphKind.BackArrow -> {
                val tipX = left + size * 0.3f
                val endX = left + size * 0.7f
                canvas.drawLine(endX, cy, tipX, cy, stroke)
                canvas.drawLine(tipX, cy, left + size * 0.49f, top + size * 0.31f, stroke)
                canvas.drawLine(tipX, cy, left + size * 0.49f, top + size * 0.69f, stroke)
            }
            EmbeddedGlyphKind.ChevronRight -> {
                canvas.drawLine(left + size * 0.4f, top + size * 0.24f, left + size * 0.62f, top + size * 0.5f, stroke)
                canvas.drawLine(left + size * 0.62f, top + size * 0.5f, left + size * 0.4f, top + size * 0.76f, stroke)
            }
            EmbeddedGlyphKind.MoreVertical -> {
                listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
                    canvas.drawCircle(cx, top + size * fraction, size * 0.06f, fill)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class EmbeddedArrowFallbackDrawable : android.graphics.drawable.Drawable(), EmbeddedPaletteDrawable {
    override fun applyPalette(colors: EmbeddedSettingsColors) {
        paint.color = colors.primary
        invalidateSelf()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        strokeWidth = 4f
        color = EmbeddedSettingsPalette.Light.primary
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        val left = box.left + box.width() * 0.28f
        val right = box.left + box.width() * 0.72f
        val center = box.top + box.height() * 0.5f
        val tip = box.left + box.width() * 0.28f
        canvas.drawLine(right, center, tip, center, paint)
        canvas.drawLine(tip, center, box.left + box.width() * 0.48f, box.top + box.height() * 0.28f, paint)
        canvas.drawLine(tip, center, box.left + box.width() * 0.48f, box.bottom - box.height() * 0.28f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

