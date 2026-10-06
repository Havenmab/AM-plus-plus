package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText
import java.util.Locale

/** Resolve the active host locale on each render, including Android per-app language. */
internal fun EmbeddedSettingsHost.localizedText(key: ModuleText, vararg arguments: Any?): String {
    val context = currentActivity() ?: application
    val locales = context.resources.configuration.locales
    val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
    return key.text(*arguments, locale = locale)
}
