package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Reports the state of the region-replacement capability.
 *
 * The rewrite itself lives in the HLE localization hooks (MediaApi storefront, catalog executor
 * arguments, the content HTTP seam and the amp-api interceptor), which are installed by
 * [HleMetadataRuntime].  This feature owns no hooks of its own — it only surfaces whether the
 * selected region actually redirects ordinary Apple Music traffic, so the health report cannot
 * claim an active region while the account region is still in force.
 *
 * It no longer reads a master switch: HLE's page gates region replacement with the picker
 * itself (its no-region option turns the rewrite off), so the region selection alone decides.
 */
internal class CatalogLanguageFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CATALOG_LANGUAGE

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        val region = settings.regionSelection
        if (!region.replacesRegion) {
            return FeatureInstallResult.active(
                "No region selected; catalog requests follow the Apple Music account",
            )
        }
        return FeatureInstallResult.active(
            "Catalog requests are localized to ${region.displayName} " +
                "(${region.catalogStorefront}); radio, lyrics and playback requests keep the " +
                "account storefront",
        )
    }
}
