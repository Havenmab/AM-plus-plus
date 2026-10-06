package dev.amenhancer.module.config

/**
 * The request-side decisions the Apple Music host derives from the two independent
 * settings controls.
 *
 * Everything derived from [region] is owned by the region control alone; everything
 * derived from the title-correction switch is owned by that switch alone.  No field
 * here depends on both controls, which is what makes 地区替换 and 歌曲名称修正
 * freely composable.
 */
data class RegionTitleRequestPlan(
    /** Content-UI language applied to MediaApi and the content-HTTP seams. */
    val contentUiLanguageSelection: Int,
    /** True when ordinary catalog traffic is redirected to the region storefront. */
    val rewritesCatalogRequests: Boolean,
    /** Catalog `Accept-Language` tag, or null to keep the account's own request language. */
    val catalogLanguage: String?,
    /** Cache namespace for the persistent region/original metadata stores. */
    val cacheNamespace: String,
    /** True when per-song titles/albums may be resolved to the song's original region. */
    val probesOriginalMetadata: Boolean,
)

/**
 * Pure projection of [RegionSelection] plus the title-correction switch onto
 * [RegionTitleRequestPlan].  Kept pure so every combination is verifiable on the
 * JVM; the host only consumes the result.
 */
object RegionTitleRequestPolicy {
    fun plan(
        region: RegionSelection,
        restoreCjkOriginalMetadata: Boolean,
    ): RegionTitleRequestPlan = RegionTitleRequestPlan(
        contentUiLanguageSelection = region.contentUiLanguageSelection,
        rewritesCatalogRequests = region.replacesRegion,
        catalogLanguage = region.catalogLanguage,
        cacheNamespace = region.cacheNamespace,
        probesOriginalMetadata = restoreCjkOriginalMetadata,
    )
}
