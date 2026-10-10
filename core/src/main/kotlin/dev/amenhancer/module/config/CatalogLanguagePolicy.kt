package dev.amenhancer.module.config

import dev.amenhancer.module.i18n.ModuleText

import java.util.Locale

/**
 * Validation and formatting for the optional language used by Apple's
 * ordinary Catalog requests.
 *
 * HLE's own original-metadata requests do not use this value: they carry a
 * request token and are localized to the detected source language instead.
 */
object CatalogLanguagePolicy {
    /** An empty value deliberately means "leave Apple Music's request language alone". */
    const val DISABLED_TARGET_LANGUAGE = ""

    fun normalize(raw: String?): String {
        val candidate = raw.orEmpty().trim().replace('_', '-')
        if (candidate.isEmpty()) return DISABLED_TARGET_LANGUAGE
        val tag = buildLocale(candidate)?.toLanguageTag().orEmpty()
        return tag.takeUnless {
            it.isBlank() || it.equals("und", ignoreCase = true)
        }.orEmpty()
    }

    fun isConfigured(raw: String?): Boolean = normalize(raw).isNotEmpty()

    fun isValid(raw: String?): Boolean = normalize(raw).isNotEmpty()

    fun displayName(tag: String?, displayLocale: Locale = Locale.getDefault()): String {
        val normalized = normalize(tag)
        if (normalized.isEmpty()) return ModuleText.CATALOG_LANGUAGE_DEFAULT.text(locale = displayLocale)
        val locale = Locale.forLanguageTag(normalized)
        val language = locale.getDisplayLanguage(displayLocale).ifBlank { normalized }
        return ModuleText.CATALOG_LANGUAGE_NAME.text(language, normalized, locale = displayLocale)
    }

    private fun buildLocale(tag: String): Locale? =
        runCatching { Locale.Builder().setLanguageTag(tag).build() }
            .getOrNull()
            ?: runCatching { Locale.forLanguageTag(tag) }.getOrNull()
}
