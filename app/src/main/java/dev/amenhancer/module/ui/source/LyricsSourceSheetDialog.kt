package dev.amenhancer.module.ui.source

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.ui.EmbeddedG3Drawable
import dev.amenhancer.module.ui.EmbeddedPanelShadow
import dev.amenhancer.module.ui.EmbeddedSettingsHost
import dev.amenhancer.module.ui.EmbeddedSettingsPage
import dev.amenhancer.module.ui.bindEmbeddedDialogActionTheme
import dev.amenhancer.module.ui.bindEmbeddedTheme
import dev.amenhancer.module.ui.dp
import dev.amenhancer.module.ui.embeddedDialogBuilder
import dev.amenhancer.module.ui.embeddedPanelWidth
import dev.amenhancer.module.ui.embeddedSpacer
import dev.amenhancer.module.ui.embeddedTextSize
import dev.amenhancer.module.ui.embeddedThemedContext
import dev.amenhancer.module.ui.embeddedTopBarHeight
import dev.amenhancer.module.ui.isEmbeddedPhone
import dev.amenhancer.module.ui.matchWidthWrapContent
import dev.amenhancer.module.ui.revealEmbeddedSettingsPage
import dev.amenhancer.module.ui.setEmbeddedTextColor
import dev.amenhancer.module.ui.configureEmbeddedDialogWindow

/**
 * The lyrics-source sheet itself.
 *
 * It mirrors [dev.amenhancer.module.ui.EmbeddedSettingsPages]'s dialog seam: one
 * transparent full-screen [AlertDialog] window whose only child is a G3 panel
 * with a contact shadow, the settings page's palette and the same top bar /
 * footer rhythm, so the sheet reads as another page of the module rather than a
 * second design language.
 *
 * The panel shrink-wraps its cards and scrolls only when the viewport is
 * smaller than the content, which is what keeps the three lanes and the search
 * card reachable on a phone while a tablet still shows the whole sheet.
 */
internal class LyricsSourceSheetDialog(
    private val host: EmbeddedSettingsHost,
    private val activity: Activity,
) : AlertDialog(embeddedThemedContext(activity, dialog = true)), LyricsSourceSheetView {

    private val controller = LyricsSourceSheetController(activity, this)
    private val panelShadow = EmbeddedPanelShadow(activity.resources.displayMetrics.density)
    private val panel = LinearLayout(activity)
    private val header = LinearLayout(activity)
    private val footer = LinearLayout(activity)
    private val bodyScroll = ScrollView(activity)
    private val content = LinearLayout(activity)
    private val songSection = LinearLayout(activity)
    private val laneSection = LinearLayout(activity)
    private val searchSection = LyricsSourceSearchSection(host, activity, controller)
    private val panelSurface = EmbeddedG3Drawable()
    private val restoreHint: TextView
    private val restoreButton: Button
    private val topDivider: View
    private val bottomDivider: View
    private var revealStop: (() -> Unit)? = null
    private var disposed = false

    private val stage = object : FrameLayout(activity) {
        private val shadowBounds = RectF()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                activity.resources.displayMetrics.widthPixels
            } else {
                MeasureSpec.getSize(widthMeasureSpec)
            }
            val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                activity.resources.displayMetrics.heightPixels
            } else {
                MeasureSpec.getSize(heightMeasureSpec)
            }
            val panelWidth = host.embeddedPanelWidth(activity, EmbeddedSettingsPage.MAIN, availableWidth)
            val maxPanelHeight = (availableHeight - host.dp(activity, 16)).coerceAtLeast(1)
            val innerWidth = (panelWidth - panel.paddingStart - panel.paddingEnd).coerceAtLeast(1)
            val headerHeight = host.embeddedTopBarHeight(activity)
            header.measure(
                MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(headerHeight, MeasureSpec.EXACTLY),
            )
            footer.measure(
                MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val chrome = headerHeight + footer.measuredHeight +
                topDivider.layoutParams.height + bottomDivider.layoutParams.height
            val maxBodyHeight = (maxPanelHeight - chrome).coerceAtLeast(1)
            val contentWidth = (innerWidth - bodyScroll.paddingStart - bodyScroll.paddingEnd).coerceAtLeast(1)
            content.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val naturalBodyHeight = content.measuredHeight + bodyScroll.paddingTop + bodyScroll.paddingBottom
            val bodyHeight = naturalBodyHeight.coerceIn(1, maxBodyHeight)
            if (bodyScroll.layoutParams?.height != bodyHeight) {
                bodyScroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    bodyHeight,
                )
            }
            panel.measure(
                MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((chrome + bodyHeight).coerceAtMost(maxPanelHeight), MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(availableWidth, availableHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val panelWidth = panel.measuredWidth
            val panelHeight = panel.measuredHeight
            val panelLeft = (width - panelWidth) / 2
            val panelTop = (height - panelHeight) / 2
            panel.layout(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (panel.width > 0 && panel.height > 0) {
                shadowBounds.set(
                    panel.left.toFloat(),
                    panel.top.toFloat(),
                    panel.right.toFloat(),
                    panel.bottom.toFloat(),
                )
                panelShadow.draw(canvas, shadowBounds, host.dp(activity, 24).toFloat())
            }
            super.dispatchDraw(canvas)
        }
    }

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        panelSurface.cornerRadius = host.dp(activity, 24).toFloat()
        panel.background = panelSurface
        panel.clipToOutline = true
        panel.bindEmbeddedTheme(activity) { colors ->
            panelSurface.setColor(colors.surface)
            panelSurface.setStroke(host.dp(activity, 1), colors.outline)
        }
        panelShadow.prepare(host.dp(activity, 24).toFloat())

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPaddingRelative(host.dp(activity, 20), 0, host.dp(activity, 8), 0)
        val title = TextView(activity).apply {
            text = LyricsSourceSheetText.title(LyricsSourceSheetText.locale(activity))
            textSize = host.embeddedTextSize(activity, 19f, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeButton = Button(activity).apply {
            bindEmbeddedDialogActionTheme(activity)
            text = LyricsSourceSheetText.close(LyricsSourceSheetText.locale(activity))
            setOnClickListener { dismiss() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            host.dp(activity, 48),
        ))

        topDivider = dividerView()
        bottomDivider = dividerView()

        bodyScroll.isVerticalScrollBarEnabled = false
        bodyScroll.isVerticalFadingEdgeEnabled = true
        bodyScroll.setFadingEdgeLength(host.dp(activity, 18))
        bodyScroll.clipToPadding = false
        bodyScroll.setPadding(
            host.dp(activity, if (host.isEmbeddedPhone(activity)) 12 else 8),
            host.dp(activity, 10),
            host.dp(activity, if (host.isEmbeddedPhone(activity)) 12 else 8),
            host.dp(activity, 12),
        )
        content.orientation = LinearLayout.VERTICAL
        songSection.orientation = LinearLayout.VERTICAL
        laneSection.orientation = LinearLayout.VERTICAL
        content.addView(songSection, host.matchWidthWrapContent())
        content.addView(host.embeddedSpacer(activity, if (host.isEmbeddedPhone(activity)) 10 else 14))
        content.addView(laneSection, host.matchWidthWrapContent())
        content.addView(host.embeddedSpacer(activity, if (host.isEmbeddedPhone(activity)) 10 else 14))
        content.addView(searchSection.root, host.matchWidthWrapContent())
        bodyScroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        footer.setPaddingRelative(host.dp(activity, 16), host.dp(activity, 6), host.dp(activity, 12), host.dp(activity, 6))
        restoreHint = TextView(activity).apply {
            textSize = host.embeddedTextSize(activity, 12f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setSingleLine(false)
            maxLines = 2
            setPadding(0, 0, host.dp(activity, 8), 0)
        }
        footer.addView(restoreHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        restoreButton = host.actionButton(
            activity = activity,
            label = LyricsSourceSheetText.restoreApple(LyricsSourceSheetText.locale(activity)),
            enabled = false,
        ) { controller.restoreAppleNative() }
        footer.addView(restoreButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            host.dp(activity, 44),
        ))

        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            host.embeddedTopBarHeight(activity),
        ))
        panel.addView(topDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(activity, 1)))
        panel.addView(bodyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            host.dp(activity, 1),
        ))
        panel.addView(bottomDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(activity, 1)))
        panel.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        stage.setOnClickListener { cancel() }
        stage.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ))

        host.configureEmbeddedDialogWindow(
            activity = activity,
            dialog = this,
            themedContext = context as android.view.ContextThemeWrapper,
            customPanel = true,
            animateStandardEntrance = false,
        )
        setOnDismissListener {
            disposed = true
            revealStop?.invoke()
            revealStop = null
            controller.dispose()
        }
    }

    private fun dividerView(): View = View(activity).apply {
        bindEmbeddedTheme(activity) { colors -> setBackgroundColor(colors.outline) }
        alpha = 0.32f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(stage, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(0)
            // The sheet owns two text fields: a dialog theme would otherwise keep
            // the IME away from this window entirely.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun show() {
        if (isShowing) return
        super.show()
        if (!isShowing) return
        controller.open()
        render()
        revealStop = panel.revealEmbeddedSettingsPage(origin = null)
    }

    override fun render() {
        if (disposed) return
        host.renderLyricsSourceSongCard(activity, songSection, controller)
        host.renderLyricsSourceLaneCards(activity, laneSection, controller)
        searchSection.render()
        renderFooter()
    }

    private fun renderFooter() {
        val hasOverrides = controller.snapshot?.hasOverrides == true
        restoreButton.isEnabled = hasOverrides && !controller.isBusy
        restoreButton.alpha = if (restoreButton.isEnabled) 1f else 0.5f
        restoreHint.text = host.lyricsSourceRestoreHint(activity, controller.snapshot)
    }

    override fun showMessage(message: String, long: Boolean) {
        if (message.isBlank()) return
        if (activity.isFinishing || activity.isDestroyed) return
        Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun confirmRestore(onConfirmed: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val locale = LyricsSourceSheetText.locale(activity)
        host.embeddedDialogBuilder(activity)
            .setTitle(LyricsSourceSheetText.restoreConfirmTitle(locale))
            .setMessage(LyricsSourceSheetText.restoreConfirmMessage(locale))
            .setNegativeButton(LyricsSourceSheetText.cancel(locale), null)
            .setPositiveButton(LyricsSourceSheetText.restore(locale)) { _, _ -> onConfirmed() }
            .show()
    }

    override fun scrollToSearch() {
        bodyScroll.post {
            if (disposed) return@post
            bodyScroll.smoothScrollTo(0, searchSection.root.top - bodyScroll.paddingTop)
            searchSection.focusArtist()
        }
    }

    override fun closeSheet() {
        if (!isShowing) return
        dismiss()
    }
}
