package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.module.R
import java.lang.ref.WeakReference

internal interface EmbeddedPaletteDrawable {
    fun applyPalette(colors: EmbeddedSettingsColors)
}

internal fun embeddedPalette(activity: Activity): EmbeddedSettingsColors =
    EmbeddedSettingsPalette.forUiMode(activity.resources.configuration.uiMode)

private class EmbeddedThemeBindings {
    val updates = mutableListOf<(View, EmbeddedSettingsColors) -> Unit>()
}

/** Bind colours to the existing view, so changing theme never recreates editable state. */
internal fun <T : View> T.bindEmbeddedTheme(
    activity: Activity,
    update: T.(EmbeddedSettingsColors) -> Unit,
): T {
    val bindings = (getTag(R.id.ampp_embedded_theme_binding) as? EmbeddedThemeBindings)
        ?: EmbeddedThemeBindings().also { setTag(R.id.ampp_embedded_theme_binding, it) }
    bindings.updates += { view, colors ->
        @Suppress("UNCHECKED_CAST")
        (view as T).update(colors)
    }
    update(embeddedPalette(activity))
    return this
}

internal fun TextView.setEmbeddedTextColor(activity: Activity, color: (EmbeddedSettingsColors) -> Int) {
    bindEmbeddedTheme(activity) { colors ->
        val normal = color(colors)
        val pressed = when (normal) {
            colors.primary -> colors.primaryPressed
            colors.accent -> colors.accentPressed
            else -> normal
        }
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(colors.disabledText, pressed, normal),
        ))
    }
}

internal fun TextView.setEmbeddedHintColor(activity: Activity, color: (EmbeddedSettingsColors) -> Int) {
    bindEmbeddedTheme(activity) { setHintTextColor(color(it)) }
}

internal fun View.setEmbeddedBackgroundColor(activity: Activity, color: (EmbeddedSettingsColors) -> Int) {
    bindEmbeddedTheme(activity) { setBackgroundColor(color(it)) }
}

internal fun embeddedControlColors(enabled: Int, disabled: Int): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
    intArrayOf(disabled, enabled),
)

internal fun embeddedSwitchThumbColors(colors: EmbeddedSettingsColors): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
    intArrayOf(colors.disabledText, colors.primary, colors.switchThumbOff),
)

internal fun embeddedSwitchTrackColors(colors: EmbeddedSettingsColors): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
    intArrayOf(colors.disabledSurface, colors.switchTrackOn, colors.switchTrackOff),
)

internal fun Button.bindEmbeddedButtonTheme(activity: Activity, tintBackground: Boolean = true) {
    setEmbeddedTextColor(activity) { it.accent }
    bindEmbeddedTheme(activity) { colors ->
        backgroundTintList = if (tintBackground) embeddedControlColors(colors.softSurface, colors.disabledSurface) else null
    }
}

internal fun ImageView.setEmbeddedImageDrawable(activity: Activity, icon: android.graphics.drawable.Drawable?) {
    setImageDrawable(icon)
    bindEmbeddedTheme(activity) { colors -> (drawable as? EmbeddedPaletteDrawable)?.applyPalette(colors) }
}

internal fun ImageView.bindEmbeddedIconTint(activity: Activity, original: Int) {
    bindEmbeddedTheme(activity) { colors ->
        imageTintList = if (drawable is EmbeddedPaletteDrawable || drawable is EmbeddedOwnColorDrawable ||
            drawable is android.graphics.drawable.BitmapDrawable) null
        else ColorStateList.valueOf(colors.iconColor(original))
    }
}

internal fun excludeEmbeddedTheme(view: View) {
    view.setTag(R.id.ampp_embedded_theme_excluded, true)
}

internal fun EditText.bindEmbeddedInputTheme(activity: Activity) {
    bindEmbeddedTheme(activity) { colors ->
        setTextColor(colors.onSurface)
        setHintTextColor(colors.onSurfaceVariant)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(colors.primary, colors.inputUnderline),
        )
        highlightColor = (colors.primary and 0x00FFFFFF) or 0x44000000
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(colors.primary) }
            textSelectHandle?.mutate()?.setTint(colors.primary)
            textSelectHandleLeft?.mutate()?.setTint(colors.primary)
            textSelectHandleRight?.mutate()?.setTint(colors.primary)
        }
    }
}

internal fun Switch.bindEmbeddedSwitchTheme(activity: Activity) {
    bindEmbeddedTheme(activity) { colors ->
        setTextColor(embeddedControlColors(colors.onSurface, colors.disabledText))
        thumbTintList = embeddedSwitchThumbColors(colors)
        trackTintList = embeddedSwitchTrackColors(colors)
    }
}

internal fun SeekBar.bindEmbeddedSeekBarTheme(activity: Activity) {
    bindEmbeddedTheme(activity) { colors ->
        thumbTintList = embeddedControlColors(colors.primary, colors.disabledText)
        progressTintList = embeddedControlColors(colors.primary, colors.disabledText)
        progressBackgroundTintList = ColorStateList.valueOf(colors.switchTrackOff)
    }
}

private fun applyEmbeddedTheme(root: View, colors: EmbeddedSettingsColors, frameworkControls: Boolean) {
    if (root.getTag(R.id.ampp_embedded_theme_excluded) == true) return
    val bindings = root.getTag(R.id.ampp_embedded_theme_binding) as? EmbeddedThemeBindings
    if (bindings != null) {
        bindings.updates.forEach { it(root, colors) }
    } else if (frameworkControls && root is TextView) {
        root.setTextColor(embeddedControlColors(if (root is Button) colors.primary else colors.onSurface, colors.disabledText))
        if (root is CheckedTextView) root.checkMarkTintList = ColorStateList.valueOf(colors.primary)
        if (root is CompoundButton) root.buttonTintList = embeddedControlColors(colors.primary, colors.disabledText)
        if (root is Button && root.id !in arrayOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)) {
            root.backgroundTintList = embeddedControlColors(colors.softSurface, colors.disabledSurface)
        }
    }
    if (root is ImageView) (root.drawable as? EmbeddedPaletteDrawable)?.applyPalette(colors)
    if (root is ViewGroup) repeat(root.childCount) { applyEmbeddedTheme(root.getChildAt(it), colors, frameworkControls) }
}

internal fun embeddedThemedContext(activity: Activity, dialog: Boolean = false): ContextThemeWrapper {
    val dark = embeddedPalette(activity).isDark
    val theme = if (dialog) {
        if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    } else {
        if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
    }
    return ContextThemeWrapper(activity, theme)
}

/** Uses framework resources because module resources cannot be resolved through the host. */
internal fun EmbeddedSettingsHost.embeddedDialogBuilder(
    activity: Activity,
    customPanel: Boolean = false,
): AlertDialog.Builder {
    refreshEmbeddedTheme(activity)
    val themedContext = embeddedThemedContext(activity, dialog = true)
    return object : AlertDialog.Builder(themedContext) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOwnerActivity(activity)
            themedDialogs.removeAll { it.get() == null }
            themedDialogs += WeakReference(dialog)
            val decor = dialog.window?.decorView ?: return@also
            decor.bindEmbeddedTheme(activity) { colors ->
                themedContext.setTheme(if (colors.isDark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
                if (!customPanel) {
                    dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                        setColor(colors.surface)
                        cornerRadius = dp(activity, 8).toFloat()
                    })
                }
            }
            if (Build.VERSION.SDK_INT >= 29) decor.isForceDarkAllowed = false
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    applyEmbeddedTheme(view, embeddedPalette(activity), frameworkControls = true)
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
    }
}

internal fun EmbeddedSettingsHost.refreshEmbeddedTheme(activity: Activity) {
    val colors = embeddedPalette(activity)
    themedDialogs.removeAll { it.get() == null }
    themedDialogs.mapNotNull { it.get() }.filter { it.ownerActivity === activity && it.isShowing }
        .forEach { dialog -> dialog.window?.decorView?.let { applyEmbeddedTheme(it, colors, frameworkControls = true) } }
    buttonReference?.get()?.let { applyEmbeddedTheme(it, colors, frameworkControls = false) }
    settingsOptionReference?.get()?.let { applyEmbeddedTheme(it, colors, frameworkControls = false) }
}
