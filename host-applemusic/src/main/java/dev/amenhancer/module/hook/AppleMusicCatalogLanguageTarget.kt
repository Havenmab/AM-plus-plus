package dev.amenhancer.module.hook

import dev.amenhancer.module.config.CatalogLanguagePolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Compatibility adapter for the former per-feature Catalog request-language target.
 *
 * The region rewrite is not installed from here: it lives in the HLE localization hooks
 * (`AppleContentLocalizationHooks`), which own the account-storefront capture, the
 * entitlement-bound request fallback and the token discriminator.  Installing a second,
 * independent set of process-wide hooks from this legacy slot would bypass all three, so the
 * adapter stays inert and only [CatalogLanguageRewritePolicy] is still used by the metadata
 * composition helpers.
 */
internal class AppleMusicCatalogLanguageTarget(
    private val symbols: TargetSymbolResolver,
    private val rawTargetLanguage: String?,
) : CatalogLanguageTarget {
    override fun install(): TargetCapabilityInstall = TargetCapabilityInstall.Degraded(
        "Legacy adapter is inert; the region rewrite is owned by the HLE localization hooks",
    )
}



internal object CatalogLanguageRewritePolicy {
    internal val rawTagKeys = setOf(
        "l",
        "lang",
        "locale",
        "storefront-language",
        "storefront_language",
    )

    fun withHeaderLanguageValue(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        if (isHleResolverRequest(original)) return original as Map<Any?, Any?>
        // Same as the content-HTTP seam: the tag goes out as configured (HLE does not map it).
        val header = CatalogLanguagePolicy.normalize(targetLanguage)
        val key = original.keys.firstOrNull {
            it?.toString()?.equals("Accept-Language", ignoreCase = true) == true
        } ?: return original as Map<Any?, Any?>
        val current = original[key]
        if (current != null && current !is String) return original as Map<Any?, Any?>
        if (current?.toString() == header) return original as Map<Any?, Any?>
        return LinkedHashMap<Any?, Any?>(original.size + 1).also {
            original.forEach(it::put)
            it[key] = header
        }
    }

    fun withRawTagLanguageValue(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        if (isHleResolverRequest(original)) return original as Map<Any?, Any?>
        val key = original.keys.firstOrNull {
            it?.toString()?.lowercase(Locale.ROOT) in rawTagKeys
        } ?: return original as Map<Any?, Any?>
        val current = original[key]
        if (current != null && current !is String) return original as Map<Any?, Any?>
        if (current?.toString() == targetLanguage) return original as Map<Any?, Any?>
        return LinkedHashMap<Any?, Any?>(original.size + 1).also {
            original.forEach(it::put)
            it[key] = targetLanguage
        }
    }

    private fun isHleResolverRequest(original: Map<*, *>): Boolean =
        original.keys.any {
            it?.toString() == AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        }
}
